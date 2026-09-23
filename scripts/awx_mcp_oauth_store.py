"""Atomic, single-writer OAuth state encrypted by Windows CurrentUser DPAPI."""
import ctypes
from ctypes import wintypes
import hashlib
import json
import os
from pathlib import Path
import tempfile


def protect(data, entropy, decrypt=False):
    if os.name != "nt":
        raise ValueError("oauth_state_requires_windows")

    class Blob(ctypes.Structure):
        _fields_ = [("size", wintypes.DWORD), ("data", ctypes.POINTER(ctypes.c_byte))]

    def blob(value):
        buffer = ctypes.create_string_buffer(value)
        return Blob(len(value), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_byte))), buffer

    source, source_buffer = blob(data)
    extra, extra_buffer = blob(entropy)
    output = Blob()
    crypt = ctypes.WinDLL("crypt32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.LocalFree.argtypes = [ctypes.c_void_p]
    kernel.LocalFree.restype = ctypes.c_void_p
    operation = crypt.CryptUnprotectData if decrypt else crypt.CryptProtectData
    operation.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.POINTER(Blob),
                          ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob)]
    operation.restype = wintypes.BOOL
    if not operation(ctypes.byref(source), None, ctypes.byref(extra), None, None,
                     1, ctypes.byref(output)):
        raise ValueError("oauth_state_unavailable")
    try:
        return ctypes.string_at(output.data, output.size)
    finally:
        kernel.LocalFree(output.data)


class OAuthStore:
    def __init__(self, path, resource, owner_key):
        self.lock = None
        try:
            import msvcrt
            self.path = Path(path)
            if not self.path.is_absolute() or not owner_key:
                raise ValueError()
            for parent in (self.path, *self.path.parents):
                if parent.exists() and (parent.stat(follow_symlinks=False).st_file_attributes & 0x400):
                    raise ValueError()
            self.path.parent.mkdir(parents=True, exist_ok=True)
            lock_path = self.path.with_suffix(self.path.suffix + ".lock")
            if lock_path.is_symlink():
                raise ValueError()
            self.lock = lock_path.open("a+b")
            if self.lock.seek(0, 2) == 0:
                self.lock.write(b"\0")
                self.lock.flush()
            self.lock.seek(0)
            msvcrt.locking(self.lock.fileno(), msvcrt.LK_NBLCK, 1)
            self.entropy = hashlib.sha256(("awx-oauth-v1\0" + resource + "\0" + owner_key).encode()).digest()
        except Exception:
            self.close()
            raise ValueError("oauth_state_unavailable") from None

    def load(self):
        try:
            if not self.path.exists():
                return None
            if self.path.stat().st_size > 4 * 1024 * 1024:
                raise ValueError()
            return json.loads(protect(self.path.read_bytes(), self.entropy, decrypt=True))
        except Exception:
            raise ValueError("oauth_state_unavailable") from None

    def save(self, value):
        temporary = None
        try:
            data = json.dumps(value, separators=(",", ":"), allow_nan=False).encode()
            if len(data) > 4 * 1024 * 1024:
                raise ValueError()
            encrypted = protect(data, self.entropy)
            with tempfile.NamedTemporaryFile(dir=self.path.parent, prefix="oauth-", suffix=".tmp", delete=False) as out:
                temporary = Path(out.name)
                out.write(encrypted)
                out.flush()
                os.fsync(out.fileno())
            os.replace(temporary, self.path)
            temporary = None
        except Exception:
            raise ValueError("oauth_state_unavailable") from None
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)

    def close(self):
        if self.lock is not None:
            self.lock.close()
            self.lock = None
