"""Create and validate sealed, read-only SourceDirective Canary packets."""

from __future__ import annotations

import argparse
import contextlib
import ctypes
import hashlib
import json
import os
import re
import shutil
import secrets
import stat
import tempfile
import time
from pathlib import Path, PureWindowsPath
from typing import Callable, Iterable


ACTIVE_ROOTS = (
    "main/java",
    "main/resources",
    "app/src/main/java_clean",
    "app/src/main/resources",
)
HANDOFF_ROOT = "data/agent-handoff/notebook"
MAX_FILES = 16
MAX_TOTAL_BYTES = 8 * 1024 * 1024
TIMEOUT_SECONDS = 30
_CHUNK_SIZE = 64 * 1024
_PACKET_NAMES = {
    "source-directive.json",
    "source-directive.sha256.txt",
    "desktop-ack.template.json",
    "manifest.json",
    "ready",
}
_FAILURE_CLASSES = (
    "broad-scan-forbidden",
    "target-not-explicit",
    "wrong-sourceset",
    "reparse-traversal-risk",
    "smb-root-identity-changed",
    "input-budget-exceeded",
    "packet-hash-mismatch",
    "changed-preimage",
    "secret-leak-risk",
    "desktop-proof-missing",
    "output-exists",
    "bundle-publication-nonatomic",
    "undeclared-source-write",
    "internal-error",
)
_SECRET_PATTERN = re.compile(
    r"(?:sk-[A-Za-z0-9_-]{8,}|bearer\s+\S+|api[-_ ]?key\s*[:=]\s*\S+|"
    r"client[-_ ]?secret\s*[:=]\s*\S+|authorization\s*[:=]\s*(?:bearer|basic)\s+\S+)",
    re.IGNORECASE,
)


class CanaryError(Exception):
    """A redacted, fail-closed Canary failure."""

    def __init__(self, failure_class: str) -> None:
        self.failure_class = failure_class
        super().__init__(failure_class)


def _windows_platform_supported() -> bool:
    return os.name == "nt"


def _require_windows_platform() -> None:
    if not _windows_platform_supported():
        raise CanaryError("bundle-publication-nonatomic")


def _canonical_json(value: object) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        .encode("utf-8")
        + b"\n"
    )


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _check_time_budget(started: float, clock: Callable[[], float]) -> None:
    if clock() - started > TIMEOUT_SECONDS:
        raise CanaryError("input-budget-exceeded")


def _has_reparse_attribute(path: Path) -> bool:
    stat_result = path.lstat()
    attributes = getattr(stat_result, "st_file_attributes", 0)
    return bool(attributes & getattr(stat_result, "FILE_ATTRIBUTE_REPARSE_POINT", 0x400))


def _identity(stat_result: os.stat_result) -> tuple[int, int, int, int]:
    return (
        stat_result.st_dev,
        stat_result.st_ino,
        stat_result.st_mode,
        getattr(stat_result, "st_file_attributes", 0),
    )


def _component_key(value: str) -> str:
    normalised = os.path.normcase(value)
    return normalised.casefold() if os.name == "nt" else normalised


def _assert_safe_directory(path: Path, failure_class: str) -> None:
    try:
        if path.is_symlink() or _has_reparse_attribute(path) or not path.is_dir():
            raise CanaryError(failure_class)
    except CanaryError:
        raise
    except OSError:
        raise CanaryError(failure_class) from None


def _normalise_relative(value: object) -> tuple[str, tuple[str, ...]]:
    if not isinstance(value, str) or not value.strip() or value.strip() == ".":
        raise CanaryError("target-not-explicit")
    if any(character in value for character in "*?["):
        raise CanaryError("broad-scan-forbidden")
    windows_path = PureWindowsPath(value)
    if windows_path.is_absolute() or windows_path.drive or value.startswith(("/", "\\")):
        raise CanaryError("target-not-explicit")
    normalised = value.replace("\\", "/")
    parts = tuple(part for part in normalised.split("/") if part)
    if not parts or any(part in (".", "..") for part in parts):
        raise CanaryError("target-not-explicit")
    relative = "/".join(parts)
    if _SECRET_PATTERN.search(relative):
        raise CanaryError("secret-leak-risk")
    return relative, parts


def _is_allowed_root(parts: tuple[str, ...]) -> bool:
    keyed = tuple(_component_key(part) for part in parts)
    return any(
        keyed[: len(root.split("/"))] == tuple(_component_key(part) for part in root.split("/"))
        for root in ACTIVE_ROOTS
    )


def _prevalidate_inspection_values(inspect_files: Iterable[object]) -> list[object]:
    """Validate the complete dynamic path batch before any filesystem work."""
    values = list(inspect_files)
    if not 1 <= len(values) <= MAX_FILES:
        raise CanaryError("input-budget-exceeded")
    seen: set[str] = set()
    for value in values:
        relative, parts = _normalise_relative(value)
        if not _is_allowed_root(parts):
            raise CanaryError("wrong-sourceset")
        relative_key = _component_key(relative)
        if relative_key in seen:
            raise CanaryError("target-not-explicit")
        seen.add(relative_key)
    return values


def _checked_target(root: Path, value: object) -> tuple[str, Path, os.stat_result]:
    relative, parts = _normalise_relative(value)
    if not _is_allowed_root(parts):
        raise CanaryError("wrong-sourceset")
    _assert_safe_directory(root, "reparse-traversal-risk")
    candidate = root
    for component in parts:
        candidate = candidate / component
        try:
            if candidate.is_symlink() or _has_reparse_attribute(candidate):
                raise CanaryError("reparse-traversal-risk")
        except CanaryError:
            raise
        except OSError:
            raise CanaryError("target-not-explicit") from None
    if candidate.is_dir():
        raise CanaryError("broad-scan-forbidden")
    try:
        pre_lstat = candidate.lstat()
    except OSError:
        raise CanaryError("target-not-explicit") from None
    if not stat.S_ISREG(pre_lstat.st_mode):
        raise CanaryError("target-not-explicit")
    return relative, candidate, pre_lstat


def _hash_target(
    root: Path,
    relative: str,
    path: Path,
    pre_lstat: os.stat_result,
    remaining_bytes: int,
    started: float,
    clock: Callable[[], float],
) -> tuple[int, str]:
    digest = hashlib.sha256()
    total = 0
    if remaining_bytes < 0 or pre_lstat.st_size > remaining_bytes:
        raise CanaryError("input-budget-exceeded")
    try:
        descriptor, pins = _open_pinned_read(root, tuple(relative.split("/")))
        try:
            opened_stat = os.fstat(descriptor)
            if _identity(pre_lstat) != _identity(opened_stat) or pre_lstat.st_size != opened_stat.st_size:
                raise CanaryError("changed-preimage")
            remaining_file = pre_lstat.st_size
            while remaining_file:
                block = os.read(descriptor, min(_CHUNK_SIZE, remaining_file))
                if not block:
                    raise CanaryError("changed-preimage")
                total += len(block)
                remaining_file -= len(block)
                if total > remaining_bytes or clock() - started > TIMEOUT_SECONDS:
                    raise CanaryError("input-budget-exceeded")
                digest.update(block)
            final_stat = os.fstat(descriptor)
            if _identity(pre_lstat) != _identity(final_stat) or pre_lstat.st_size != final_stat.st_size:
                raise CanaryError("changed-preimage")
        finally:
            os.close(descriptor)
            _close_pins(pins)
    except CanaryError:
        raise
    except OSError:
        raise CanaryError("changed-preimage") from None
    if clock() - started > TIMEOUT_SECONDS:
        raise CanaryError("input-budget-exceeded")
    return total, digest.hexdigest()


def _inspect_targets(
    root: Path,
    inspect_files: Iterable[object],
    clock: Callable[[], float],
    *,
    started: float | None = None,
) -> tuple[list[dict[str, object]], int]:
    values = list(inspect_files)
    if not 1 <= len(values) <= MAX_FILES:
        raise CanaryError("input-budget-exceeded")
    if started is None:
        started = clock()
    seen: set[str] = set()
    targets: list[dict[str, object]] = []
    total_bytes = 0
    for value in values:
        relative, target, pre_lstat = _checked_target(root, value)
        relative_key = _component_key(relative)
        if relative_key in seen:
            raise CanaryError("target-not-explicit")
        seen.add(relative_key)
        size, digest = _hash_target(
            root,
            relative,
            target,
            pre_lstat,
            MAX_TOTAL_BYTES - total_bytes,
            started,
            clock,
        )
        total_bytes += size
        if total_bytes > MAX_TOTAL_BYTES:
            raise CanaryError("input-budget-exceeded")
        targets.append({"path": relative, "size": size, "sha256": digest})
    return targets, total_bytes


def _assert_output_boundary(root: Path, output_dir: Path) -> None:
    """Allow only a named packet below the canonical Notebook handoff root."""
    root_absolute = Path(os.path.abspath(root))
    output_absolute = Path(os.path.abspath(output_dir))
    try:
        relative_parts = output_absolute.relative_to(root_absolute).parts
    except ValueError:
        raise CanaryError("undeclared-source-write") from None
    if not relative_parts:
        raise CanaryError("undeclared-source-write")
    keyed = tuple(_component_key(part) for part in relative_parts)
    handoff_parts = tuple(_component_key(part) for part in HANDOFF_ROOT.split("/"))
    if len(keyed) != len(handoff_parts) + 1 or keyed[: len(handoff_parts)] != handoff_parts:
        raise CanaryError("undeclared-source-write")
    _assert_safe_directory(root_absolute, "undeclared-source-write")
    candidate = root_absolute
    for component in relative_parts:
        candidate = candidate / component
        if not candidate.exists():
            break
        try:
            if candidate.is_symlink() or _has_reparse_attribute(candidate):
                raise CanaryError("undeclared-source-write")
        except CanaryError:
            raise
        except OSError:
            raise CanaryError("undeclared-source-write") from None
    for active_root in ACTIVE_ROOTS:
        active_parts = tuple(_component_key(part) for part in active_root.split("/"))
        if keyed[: len(active_parts)] == active_parts or active_parts[: len(keyed)] == keyed:
            raise CanaryError("undeclared-source-write")


_FILE_ATTRIBUTE_REPARSE_POINT = 0x400
_FILE_ATTRIBUTE_DIRECTORY = 0x10
_FILE_ATTRIBUTE_NORMAL = 0x80
_FILE_LIST_DIRECTORY = _FILE_READ_DATA = 0x0001
_FILE_ADD_FILE = _FILE_WRITE_DATA = 0x0002
_FILE_ADD_SUBDIRECTORY = 0x0004
_FILE_TRAVERSE = 0x0020
_FILE_READ_ATTRIBUTES = 0x0080
_FILE_WRITE_ATTRIBUTES = 0x0100
_DELETE = 0x00010000
_SYNCHRONIZE = 0x00100000
_FILE_SHARE_READ = 0x1
_FILE_SHARE_WRITE = 0x2
_FILE_OPEN = 1
_FILE_CREATE = 2
_FILE_DIRECTORY_FILE = 0x00000001
_FILE_SYNCHRONOUS_IO_NONALERT = 0x00000020
_FILE_NON_DIRECTORY_FILE = 0x00000040
_FILE_OPEN_REPARSE_POINT = 0x00200000
_OBJ_CASE_INSENSITIVE = 0x40
_OBJ_DONT_REPARSE = 0x1000
_FILE_ATTRIBUTE_TAG_INFO_CLASS = 9
_FILE_RENAME_INFORMATION_CLASS = 10
_FILE_DISPOSITION_INFORMATION_CLASS = 13

_DIR_READ_ACCESS = _FILE_LIST_DIRECTORY | _FILE_TRAVERSE | _FILE_READ_ATTRIBUTES | _SYNCHRONIZE
_DIR_PARENT_ACCESS = _DIR_READ_ACCESS | _FILE_ADD_FILE | _FILE_ADD_SUBDIRECTORY | _FILE_WRITE_ATTRIBUTES
_DIR_STAGING_ACCESS = _DIR_PARENT_ACCESS | _DELETE
_FILE_READ_ACCESS = _FILE_READ_DATA | _FILE_READ_ATTRIBUTES | _SYNCHRONIZE
_FILE_CREATE_ACCESS = _FILE_READ_DATA | _FILE_WRITE_DATA | _FILE_READ_ATTRIBUTES | _FILE_WRITE_ATTRIBUTES | _SYNCHRONIZE


class _UNICODE_STRING(ctypes.Structure):
    _fields_ = [
        ("Length", ctypes.c_ushort),
        ("MaximumLength", ctypes.c_ushort),
        ("Buffer", ctypes.c_wchar_p),
    ]


class _OBJECT_ATTRIBUTES(ctypes.Structure):
    _fields_ = [
        ("Length", ctypes.c_ulong),
        ("RootDirectory", ctypes.c_void_p),
        ("ObjectName", ctypes.POINTER(_UNICODE_STRING)),
        ("Attributes", ctypes.c_ulong),
        ("SecurityDescriptor", ctypes.c_void_p),
        ("SecurityQualityOfService", ctypes.c_void_p),
    ]


class _IO_STATUS_UNION(ctypes.Union):
    _fields_ = [("Status", ctypes.c_int32), ("Pointer", ctypes.c_void_p)]


class _IO_STATUS_BLOCK(ctypes.Structure):
    _anonymous_ = ("Result",)
    _fields_ = [("Result", _IO_STATUS_UNION), ("Information", ctypes.c_size_t)]


class _FILE_ATTRIBUTE_TAG_INFO(ctypes.Structure):
    _fields_ = [("FileAttributes", ctypes.c_uint32), ("ReparseTag", ctypes.c_uint32)]


class _FILE_RENAME_INFORMATION(ctypes.Structure):
    _fields_ = [
        ("ReplaceIfExists", ctypes.c_ubyte),
        ("RootDirectory", ctypes.c_void_p),
        ("FileNameLength", ctypes.c_ulong),
        ("FileName", ctypes.c_wchar * 1),
    ]


class _FILE_DISPOSITION_INFORMATION(ctypes.Structure):
    _fields_ = [("DeleteFile", ctypes.c_ubyte)]


def _child_component(name: str) -> str:
    if not isinstance(name, str) or not name or name in (".", "..") or any(char in name for char in "\0\\/:"):
        raise CanaryError("reparse-traversal-risk")
    return name


def _windows_api():
    try:
        ntdll = ctypes.WinDLL("ntdll", use_last_error=True)
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    except (AttributeError, OSError) as error:
        code = getattr(error, "winerror", None) or getattr(error, "errno", None) or 0
        raise OSError(code, f"windows-native-api-unavailable-dos-{code}") from None
    ntdll.NtCreateFile.argtypes = (
        ctypes.POINTER(ctypes.c_void_p), ctypes.c_ulong, ctypes.POINTER(_OBJECT_ATTRIBUTES),
        ctypes.POINTER(_IO_STATUS_BLOCK), ctypes.POINTER(ctypes.c_int64), ctypes.c_ulong,
        ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_void_p, ctypes.c_ulong,
    )
    ntdll.NtCreateFile.restype = ctypes.c_int32
    ntdll.NtSetInformationFile.argtypes = (
        ctypes.c_void_p, ctypes.POINTER(_IO_STATUS_BLOCK), ctypes.c_void_p,
        ctypes.c_ulong, ctypes.c_int,
    )
    ntdll.NtSetInformationFile.restype = ctypes.c_int32
    ntdll.RtlNtStatusToDosError.argtypes = (ctypes.c_int32,)
    ntdll.RtlNtStatusToDosError.restype = ctypes.c_ulong
    kernel32.GetFileInformationByHandleEx.argtypes = (
        ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p, ctypes.c_uint32,
    )
    kernel32.GetFileInformationByHandleEx.restype = ctypes.c_int
    kernel32.CloseHandle.argtypes = (ctypes.c_void_p,)
    kernel32.CloseHandle.restype = ctypes.c_int
    return ntdll, kernel32


def _raise_nt_error(status: int, operation: str) -> None:
    ntdll, _ = _windows_api()
    dos_error = int(ntdll.RtlNtStatusToDosError(ctypes.c_int32(status)))
    unsigned = status & 0xFFFFFFFF
    raise OSError(dos_error, f"{operation}-ntstatus-{unsigned:08x}-dos-{dos_error}")


def _close_windows_handle(handle: int) -> None:
    if handle:
        _, kernel32 = _windows_api()
        kernel32.CloseHandle(ctypes.c_void_p(handle))


def _windows_handle_attributes(handle: int) -> int:
    """Read attributes from an already-open handle; never reopen a pathname."""
    _, kernel32 = _windows_api()
    info = _FILE_ATTRIBUTE_TAG_INFO()
    if not kernel32.GetFileInformationByHandleEx(
        ctypes.c_void_p(handle), _FILE_ATTRIBUTE_TAG_INFO_CLASS,
        ctypes.byref(info), ctypes.sizeof(info),
    ):
        code = ctypes.get_last_error()
        raise OSError(code, f"handle-attribute-query-dos-{code}")
    return int(info.FileAttributes)


def _unicode_object(name: str) -> tuple[ctypes.Array, _UNICODE_STRING]:
    encoded = name.encode("utf-16-le")
    buffer = ctypes.create_unicode_buffer(name)
    value = _UNICODE_STRING(len(encoded), len(encoded) + 2, ctypes.cast(buffer, ctypes.c_wchar_p))
    return buffer, value


def _nt_create(
    parent: int | None,
    name: str,
    access: int,
    disposition: int,
    options: int,
    *,
    directory: bool,
    one_component: bool = True,
    dont_reparse: bool = True,
) -> int:
    if one_component:
        _child_component(name)
    name_buffer, unicode_name = _unicode_object(name)
    attributes = _OBJECT_ATTRIBUTES(
        ctypes.sizeof(_OBJECT_ATTRIBUTES), ctypes.c_void_p(parent or 0),
        ctypes.pointer(unicode_name), _OBJ_CASE_INSENSITIVE | (_OBJ_DONT_REPARSE if dont_reparse else 0),
        None, None,
    )
    io_status = _IO_STATUS_BLOCK()
    handle = ctypes.c_void_p()
    ntdll, _ = _windows_api()
    status = int(ntdll.NtCreateFile(
        ctypes.byref(handle), access, ctypes.byref(attributes), ctypes.byref(io_status),
        None, _FILE_ATTRIBUTE_NORMAL, _FILE_SHARE_READ | _FILE_SHARE_WRITE,
        disposition, options, None, 0,
    ))
    del name_buffer
    if status < 0:
        _raise_nt_error(status, "NtCreateFile")
    opened = int(handle.value or 0)
    if not opened:
        raise OSError(0, "NtCreateFile-null-handle")
    try:
        attrs = _windows_handle_attributes(opened)
        if attrs & _FILE_ATTRIBUTE_REPARSE_POINT:
            raise CanaryError("reparse-traversal-risk")
        is_directory = bool(attrs & _FILE_ATTRIBUTE_DIRECTORY)
        if is_directory != directory:
            raise CanaryError("target-not-explicit")
        return opened
    except BaseException:
        _close_windows_handle(opened)
        raise


def _windows_anchor_and_components(path: Path) -> tuple[str, tuple[str, ...]]:
    absolute = PureWindowsPath(os.path.abspath(path))
    parts = absolute.parts
    if not parts or not absolute.anchor:
        raise OSError(0, "windows-anchor-unavailable-dos-0")
    anchor = parts[0]
    if anchor.startswith("\\\\"):
        native_anchor = "\\??\\UNC\\" + anchor.lstrip("\\")
    else:
        native_anchor = "\\??\\" + anchor
    return native_anchor, tuple(parts[1:])


def _open_pinned_directory(path: Path, *, writable_parent: bool = False) -> list[int]:
    _require_windows_platform()
    absolute = Path(os.path.abspath(path))
    pins: list[int] = []
    try:
        anchor, components = _windows_anchor_and_components(absolute)
        anchor_access = _DIR_PARENT_ACCESS if writable_parent and not components else _DIR_READ_ACCESS
        pins.append(_nt_create(
            None, anchor, anchor_access, _FILE_OPEN,
            _FILE_DIRECTORY_FILE | _FILE_OPEN_REPARSE_POINT | _FILE_SYNCHRONOUS_IO_NONALERT,
            directory=True, one_component=False, dont_reparse=False,
        ))
        for index, component in enumerate(components):
            access = _DIR_PARENT_ACCESS if writable_parent and index == len(components) - 1 else _DIR_READ_ACCESS
            pins.append(_nt_create(
                pins[-1], component, access, _FILE_OPEN,
                _FILE_DIRECTORY_FILE | _FILE_OPEN_REPARSE_POINT | _FILE_SYNCHRONOUS_IO_NONALERT,
                directory=True,
            ))
        return pins
    except BaseException:
        _close_pins(pins)
        raise


def _open_relative_directory(parent: int, component: str) -> int:
    _require_windows_platform()
    _child_component(component)
    return _nt_create(
        parent, component, _DIR_READ_ACCESS, _FILE_OPEN,
        _FILE_DIRECTORY_FILE | _FILE_OPEN_REPARSE_POINT | _FILE_SYNCHRONOUS_IO_NONALERT,
        directory=True,
    )


def _windows_handle_to_fd(handle: int, flags: int) -> int:
    import msvcrt
    try:
        return msvcrt.open_osfhandle(handle, flags | getattr(os, "O_BINARY", 0))
    except BaseException:
        _close_windows_handle(handle)
        raise


def _open_relative_file(parent: int, component: str, *, create: bool = False) -> int:
    _require_windows_platform()
    _child_component(component)
    handle = _nt_create(
        parent, component, _FILE_CREATE_ACCESS if create else _FILE_READ_ACCESS,
        _FILE_CREATE if create else _FILE_OPEN,
        _FILE_NON_DIRECTORY_FILE | _FILE_OPEN_REPARSE_POINT | _FILE_SYNCHRONOUS_IO_NONALERT,
        directory=False,
    )
    return _windows_handle_to_fd(handle, os.O_RDWR if create else os.O_RDONLY)


def _open_pinned_read(root: Path, parts: tuple[str, ...]) -> tuple[int, list[int]]:
    """Return a readable descriptor plus every pinned ancestor descriptor/handle."""
    if not parts:
        raise CanaryError("target-not-explicit")
    pins = _open_pinned_directory(root)
    try:
        for component in parts[:-1]:
            pins.append(_open_relative_directory(pins[-1], component))
        return _open_relative_file(pins[-1], parts[-1]), pins
    except BaseException:
        _close_pins(pins)
        raise


def _close_pins(pins: list[int]) -> None:
    for pin in reversed(pins):
        _close_windows_handle(pin)


def _read_pinned_file(
    root: Path,
    parts: tuple[str, ...],
    *,
    parent_pin: int | None = None,
    started: float | None = None,
    clock: Callable[[], float] = time.monotonic,
    max_bytes: int | None = None,
) -> bytes:
    owned_pins: list[int] = []
    if parent_pin is None:
        descriptor, owned_pins = _open_pinned_read(root, parts)
    else:
        if len(parts) != 1:
            raise CanaryError("packet-hash-mismatch")
        descriptor = _open_relative_file(parent_pin, parts[0])
    try:
        opened_stat = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened_stat.st_mode)
            or opened_stat.st_size < 0
            or (max_bytes is not None and opened_stat.st_size > max_bytes)
        ):
            raise CanaryError("packet-hash-mismatch")
        chunks: list[bytes] = []
        remaining = opened_stat.st_size
        while remaining:
            if started is not None:
                _check_time_budget(started, clock)
            block = os.read(descriptor, min(_CHUNK_SIZE, remaining))
            if not block:
                raise CanaryError("packet-hash-mismatch")
            if started is not None:
                _check_time_budget(started, clock)
            chunks.append(block)
            remaining -= len(block)
        final_stat = os.fstat(descriptor)
        if _identity(opened_stat) != _identity(final_stat) or opened_stat.st_size != final_stat.st_size:
            raise CanaryError("packet-hash-mismatch")
        return b"".join(chunks)
    finally:
        os.close(descriptor)
        _close_pins(owned_pins)


def _create_pinned_staging(root: Path, output_dir: Path) -> tuple[Path, list[int]]:
    """Atomically create a random child relative to the pinned output parent."""
    root_absolute = Path(os.path.abspath(root))
    output_absolute = Path(os.path.abspath(output_dir))
    output_absolute.parent.relative_to(root_absolute)
    pins = _open_pinned_directory(output_absolute.parent, writable_parent=True)
    name = f".{output_absolute.name}.staging-{secrets.token_hex(16)}"
    try:
        pins.append(_nt_create(
            pins[-1], name, _DIR_STAGING_ACCESS, _FILE_CREATE,
            _FILE_DIRECTORY_FILE | _FILE_OPEN_REPARSE_POINT | _FILE_SYNCHRONOUS_IO_NONALERT,
            directory=True,
        ))
        return output_absolute.parent / name, pins
    except BaseException:
        _close_pins(pins)
        raise


def _write_exclusive(directory_pin: int, name: str, data: bytes) -> None:
    descriptor = _open_relative_file(directory_pin, name, create=True)
    try:
        view = memoryview(data)
        while view:
            written = os.write(descriptor, view)
            view = view[written:]
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _read_relative_file(directory_pin: int, name: str) -> bytes:
    descriptor = _open_relative_file(directory_pin, name)
    try:
        chunks: list[bytes] = []
        while True:
            block = os.read(descriptor, _CHUNK_SIZE)
            if not block:
                return b"".join(chunks)
            chunks.append(block)
    finally:
        os.close(descriptor)


def _nt_set_disposition(handle: int) -> None:
    info = _FILE_DISPOSITION_INFORMATION(1)
    io_status = _IO_STATUS_BLOCK()
    ntdll, _ = _windows_api()
    status = int(ntdll.NtSetInformationFile(
        ctypes.c_void_p(handle), ctypes.byref(io_status), ctypes.byref(info),
        ctypes.sizeof(info), _FILE_DISPOSITION_INFORMATION_CLASS,
    ))
    if status < 0:
        _raise_nt_error(status, "NtSetInformationFile-disposition")


def _windows_rename_no_replace(staging_handle: int, parent_handle: int, final_name: str) -> None:
    _child_component(final_name)
    name_bytes = final_name.encode("utf-16-le")
    used = _FILE_RENAME_INFORMATION.FileName.offset + len(name_bytes)
    buffer = ctypes.create_string_buffer(used)
    header = _FILE_RENAME_INFORMATION.from_buffer(buffer)
    header.ReplaceIfExists = 0
    header.RootDirectory = ctypes.c_void_p(parent_handle)
    header.FileNameLength = len(name_bytes)
    ctypes.memmove(ctypes.addressof(buffer) + _FILE_RENAME_INFORMATION.FileName.offset, name_bytes, len(name_bytes))
    io_status = _IO_STATUS_BLOCK()
    ntdll, _ = _windows_api()
    status = int(ntdll.NtSetInformationFile(
        ctypes.c_void_p(staging_handle), ctypes.byref(io_status), ctypes.byref(buffer),
        used, _FILE_RENAME_INFORMATION_CLASS,
    ))
    if status < 0:
        _raise_nt_error(status, "NtSetInformationFile-rename")


def _publish(staging: Path, output_dir: Path, pins: list[int]) -> None:
    parent_pin, staging_pin = pins[-2], pins[-1]
    _windows_rename_no_replace(staging_pin, parent_pin, output_dir.name)


def _cleanup_staging(staging: Path, pins: list[int], names: Iterable[str]) -> None:
    if len(pins) < 2:
        return
    parent_pin, staging_pin = pins[-2], pins[-1]
    for name in reversed(tuple(names)):
        try:
            handle = _nt_create(
                staging_pin, name, _DELETE | _FILE_READ_ATTRIBUTES | _SYNCHRONIZE,
                _FILE_OPEN, _FILE_NON_DIRECTORY_FILE | _FILE_OPEN_REPARSE_POINT | _FILE_SYNCHRONOUS_IO_NONALERT,
                directory=False,
            )
        except OSError:
            continue
        try:
            _nt_set_disposition(handle)
        finally:
            _close_windows_handle(handle)
    try:
        _nt_set_disposition(staging_pin)
    except OSError:
        pass


def _fixed_directive(
    directive_id: str, branch: str, inspection_targets: list[dict[str, object]]
) -> dict[str, object]:
    return {
        "schemaVersion": "1.0",
        "directiveId": directive_id,
        "canonicalWorkspace": "Y:\\",
        "provenRoot": "Y:\\",
        "provenBranch": branch,
        "sourceOwner": "desktop",
        "activeSourceSets": list(ACTIVE_ROOTS),
        "targetFiles": [],
        "inspectionTargets": inspection_targets,
        "authorizedMutation": False,
        "sourceWriteRoot": None,
        "callPathOrBoundary": "handoff-only-no-source-call-path",
        "beforeBehavior": "No source mutation requested; Desktop proof absent.",
        "afterBehavior": "No source mutation performed; Desktop validates packet integrity.",
        "excludedFilesAndMirrors": ["all undeclared files", "inactive/reference source mirrors"],
        "publicApiChange": "forbidden",
        "secretMutation": "forbidden",
        "redTest": "Reject any mutation request, broad scan, or packet tamper.",
        "greenTest": "Validate the sealed no-change packet and unchanged explicit preimages.",
        "exactVerificationCommands": "python scripts/awx_notebook_source_directive_canary.py validate --root Y:\\ --packet-dir Y:\\<packet-directory>",
        "expectedEvidence": "packet/hash/preimage validation plus a Desktop-owned external ACK",
        "failureClassifications": list(_FAILURE_CLASSES),
        "rollback": "delete only this Canary packet directory",
        "patchdropContract": "not-applicable",
        "desktopFinalProof": "evidence_needed",
        "runtimeLineageVerdict": "HOLD",
    }


def _result(directive_id: str, targets: list[dict[str, object]], total: int, directive_hash: str, manifest_hash: str) -> dict[str, object]:
    return {
        "valid": True,
        "canonicalWorkspace": "Y:\\",
        "directiveId": directive_id,
        "inspectionTargetCount": len(targets),
        "totalBytes": total,
        "sourceDirectiveSha256": directive_hash,
        "manifestSha256": manifest_hash,
        "authorizedMutation": False,
        "sourceWriteRoot": None,
        "targetFiles": [],
        "sourceOwner": "desktop",
        "desktopFinalProof": "evidence_needed",
        "runtimeLineageVerdict": "HOLD",
    }


def prepare_packet(
    root: Path | str,
    output_dir: Path | str,
    directive_id: str,
    branch: str,
    identity_verified: bool,
    identity_reason: str,
    inspect_files: Iterable[object],
    *,
    clock: Callable[[], float] = time.monotonic,
    fault_at: str | None = None,
) -> dict[str, object]:
    """Seal a read-only packet without reading undeclared source files."""
    started = clock()
    _require_windows_platform()
    _check_time_budget(started, clock)
    if not identity_verified or identity_reason != "match":
        raise CanaryError("smb-root-identity-changed")
    if (
        not isinstance(directive_id, str)
        or not directive_id.strip()
        or not isinstance(branch, str)
        or not branch.strip()
    ):
        raise CanaryError("target-not-explicit")
    if _SECRET_PATTERN.search(directive_id) or _SECRET_PATTERN.search(branch):
        raise CanaryError("secret-leak-risk")
    root_path = Path(root)
    output_path = Path(output_dir)
    try:
        inspect_values = _prevalidate_inspection_values(inspect_files)
        _check_time_budget(started, clock)
        _assert_output_boundary(root_path, output_path)
        _check_time_budget(started, clock)
        if output_path.exists():
            raise CanaryError("output-exists")
        targets, total_bytes = _inspect_targets(
            root_path,
            inspect_values,
            clock,
            started=started,
        )
        _check_time_budget(started, clock)
    except CanaryError:
        raise
    except OSError:
        raise CanaryError("bundle-publication-nonatomic") from None
    directive = _fixed_directive(directive_id, branch, targets)
    directive_bytes = _canonical_json(directive)
    directive_hash = _sha256(directive_bytes)
    sidecar_bytes = (directive_hash + "\n").encode("ascii")
    ack = {
        "schemaVersion": "1.0",
        "directiveId": directive_id,
        "sourceDirectiveSha256": directive_hash,
        "verifierRole": "desktop",
        "status": "evidence_needed",
        "acknowledgedAt": None,
        "sourceMutationObserved": False,
        "failureClass": "desktop-proof-missing",
    }
    ack_bytes = _canonical_json(ack)
    manifest = {
        "schemaVersion": "1.0",
        "bundleId": directive_id,
        "canonicalWorkspace": "Y:\\",
        "authorizedMutation": False,
        "sourceWriteRoot": None,
        "targetFiles": [],
        "desktopFinalProof": "evidence_needed",
        "payloadHashes": {
            "source-directive.json": directive_hash,
            "source-directive.sha256.txt": _sha256(sidecar_bytes),
            "desktop-ack.template.json": _sha256(ack_bytes),
        },
    }
    manifest_bytes = _canonical_json(manifest)
    manifest_hash = _sha256(manifest_bytes)
    ready_bytes = (manifest_hash + "\n").encode("ascii")
    payload = {
        "source-directive.json": directive_bytes,
        "source-directive.sha256.txt": sidecar_bytes,
        "desktop-ack.template.json": ack_bytes,
        "manifest.json": manifest_bytes,
    }
    if sum(len(data) for data in payload.values()) + len(ready_bytes) > MAX_TOTAL_BYTES:
        raise CanaryError("input-budget-exceeded")
    _check_time_budget(started, clock)
    staging: Path | None = None
    pins: list[int] = []
    created_names: list[str] = []
    published = False
    try:
        _check_time_budget(started, clock)
        staging, pins = _create_pinned_staging(root_path, output_path)
        _check_time_budget(started, clock)
        for name, data in payload.items():
            created_names.append(name)
            _write_exclusive(pins[-1], name, data)
            _check_time_budget(started, clock)
        for name, data in payload.items():
            if _sha256(_read_relative_file(pins[-1], name)) != _sha256(data):
                raise CanaryError("bundle-publication-nonatomic")
            _check_time_budget(started, clock)
        if fault_at == "before-ready":
            raise CanaryError("bundle-publication-nonatomic")
        created_names.append("ready")
        _write_exclusive(pins[-1], "ready", ready_bytes)
        _check_time_budget(started, clock)
        if fault_at == "before-rename":
            raise CanaryError("bundle-publication-nonatomic")
        _check_time_budget(started, clock)
        _publish(staging, output_path, pins)
        published = True
    except CanaryError:
        raise
    except OSError:
        raise CanaryError("bundle-publication-nonatomic") from None
    finally:
        if not published and staging is not None and pins:
            _cleanup_staging(staging, pins, created_names)
        _close_pins(pins)
    return _result(directive_id, targets, total_bytes, directive_hash, manifest_hash)


def _load_packet_json(data: bytes) -> object:
    try:
        value = json.loads(data.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise CanaryError("packet-hash-mismatch") from None
    if _canonical_json(value) != data:
        raise CanaryError("packet-hash-mismatch")
    return value


def _require_fixed(value: dict[str, object], expected: dict[str, object]) -> None:
    if any(value.get(key) != item for key, item in expected.items()):
        raise CanaryError("packet-hash-mismatch")


def validate_packet(root: Path | str, packet_dir: Path | str, *, clock: Callable[[], float] = time.monotonic) -> dict[str, object]:
    """Validate a sealed packet and rehash only its declared explicit inputs."""
    started = clock()
    _require_windows_platform()
    _check_time_budget(started, clock)
    root_path = Path(root)
    packet_path = Path(packet_dir)
    _assert_output_boundary(root_path, packet_path)
    _check_time_budget(started, clock)
    packet_pins: list[int] = []
    try:
        packet_pins = _open_pinned_directory(packet_path)
        _check_time_budget(started, clock)
        entries: list[Path] = []
        for entry in packet_path.iterdir():
            if len(entries) >= 6:
                raise CanaryError("packet-hash-mismatch")
            entries.append(entry)
            _check_time_budget(started, clock)
        if len(entries) != 5 or {entry.name for entry in entries} != _PACKET_NAMES:
            raise CanaryError("packet-hash-mismatch")
        raw: dict[str, bytes] = {}
        packet_bytes = 0
        for entry in entries:
            data = _read_pinned_file(
                packet_path,
                (entry.name,),
                parent_pin=packet_pins[-1],
                started=started,
                clock=clock,
                max_bytes=MAX_TOTAL_BYTES - packet_bytes,
            )
            raw[entry.name] = data
            packet_bytes += len(data)
            _check_time_budget(started, clock)
    except CanaryError as error:
        if error.failure_class == "input-budget-exceeded":
            raise
        raise CanaryError("packet-hash-mismatch") from None
    except OSError:
        raise CanaryError("packet-hash-mismatch") from None
    finally:
        _close_pins(packet_pins)
    directive_hash = _sha256(raw["source-directive.json"])
    if raw["source-directive.sha256.txt"] != (directive_hash + "\n").encode("ascii"):
        raise CanaryError("packet-hash-mismatch")
    directive = _load_packet_json(raw["source-directive.json"])
    _check_time_budget(started, clock)
    ack = _load_packet_json(raw["desktop-ack.template.json"])
    _check_time_budget(started, clock)
    manifest = _load_packet_json(raw["manifest.json"])
    _check_time_budget(started, clock)
    if not isinstance(directive, dict) or not isinstance(ack, dict) or not isinstance(manifest, dict):
        raise CanaryError("packet-hash-mismatch")
    manifest_hash = _sha256(raw["manifest.json"])
    if raw["ready"] != (manifest_hash + "\n").encode("ascii"):
        raise CanaryError("packet-hash-mismatch")
    directive_id = directive.get("directiveId")
    branch = directive.get("provenBranch")
    inspection_targets = directive.get("inspectionTargets")
    if (
        not isinstance(directive_id, str) or not directive_id.strip()
        or not isinstance(branch, str) or not branch.strip()
        or _SECRET_PATTERN.search(directive_id) or _SECRET_PATTERN.search(branch)
        or not isinstance(inspection_targets, list) or not 1 <= len(inspection_targets) <= MAX_FILES
        or directive != _fixed_directive(directive_id, branch, inspection_targets)
    ):
        raise CanaryError("packet-hash-mismatch")
    declared_total = 0
    for declared in inspection_targets:
        if (
            not isinstance(declared, dict)
            or set(declared) != {"path", "size", "sha256"}
            or not isinstance(declared.get("size"), int)
            or isinstance(declared.get("size"), bool)
            or declared["size"] < 0
        ):
            raise CanaryError("packet-hash-mismatch")
        declared_total += declared["size"]
        if declared_total > MAX_TOTAL_BYTES:
            raise CanaryError("packet-hash-mismatch")
        _check_time_budget(started, clock)
    try:
        _prevalidate_inspection_values(declared["path"] for declared in inspection_targets)
    except CanaryError:
        raise CanaryError("packet-hash-mismatch") from None
    _check_time_budget(started, clock)
    expected_ack = {
        "schemaVersion": "1.0", "directiveId": directive_id, "sourceDirectiveSha256": directive_hash,
        "verifierRole": "desktop", "status": "evidence_needed", "acknowledgedAt": None,
        "sourceMutationObserved": False, "failureClass": "desktop-proof-missing",
    }
    if ack != expected_ack:
        raise CanaryError("packet-hash-mismatch")
    expected_payload_hashes = {
        "source-directive.json": directive_hash,
        "source-directive.sha256.txt": _sha256(raw["source-directive.sha256.txt"]),
        "desktop-ack.template.json": _sha256(raw["desktop-ack.template.json"]),
    }
    expected_manifest = {
        "schemaVersion": "1.0", "bundleId": directive_id, "canonicalWorkspace": "Y:\\",
        "authorizedMutation": False, "sourceWriteRoot": None, "targetFiles": [],
        "desktopFinalProof": "evidence_needed", "payloadHashes": expected_payload_hashes,
    }
    if manifest != expected_manifest:
        raise CanaryError("packet-hash-mismatch")
    seen: set[str] = set()
    total_bytes = 0
    for declared in inspection_targets:
        try:
            relative, target, pre_lstat = _checked_target(root_path, declared["path"])
            size, digest = _hash_target(
                root_path,
                relative,
                target,
                pre_lstat,
                MAX_TOTAL_BYTES - total_bytes,
                started,
                clock,
            )
        except CanaryError as error:
            if error.failure_class in {"target-not-explicit", "wrong-sourceset", "reparse-traversal-risk", "broad-scan-forbidden"}:
                raise CanaryError("packet-hash-mismatch") from None
            raise
        relative_key = _component_key(relative)
        if relative_key in seen or declared.get("size") != size or declared.get("sha256") != digest:
            raise CanaryError("changed-preimage")
        seen.add(relative_key)
        total_bytes += size
        if total_bytes > MAX_TOTAL_BYTES:
            raise CanaryError("changed-preimage")
        _check_time_budget(started, clock)
    _check_time_budget(started, clock)
    return {
        "valid": True, "canonicalWorkspace": "Y:\\", "directiveId": directive_id,
        "inspectionTargetCount": len(inspection_targets), "totalBytes": total_bytes,
        "sourceDirectiveSha256": directive_hash, "manifestSha256": manifest_hash,
        "authorizedMutation": False, "sourceWriteRoot": None, "targetFiles": [], "sourceOwner": "desktop",
        "desktopFinalProof": "evidence_needed", "runtimeLineageVerdict": "HOLD",
        "evidenceState": "desktop-proof-missing",
    }


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Create or validate a read-only SourceDirective Canary packet.")
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("prepare")
    prepare.add_argument("--root", required=True)
    prepare.add_argument("--output-dir", required=True)
    prepare.add_argument("--directive-id", required=True)
    prepare.add_argument("--branch", required=True)
    prepare.add_argument("--identity-verified", required=True, choices=("true", "false"))
    prepare.add_argument("--identity-reason", required=True)
    prepare.add_argument("--inspect-file", action="append", required=True)
    validate = commands.add_parser("validate")
    validate.add_argument("--root", required=True)
    validate.add_argument("--packet-dir", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "prepare":
            result = prepare_packet(
                args.root, args.output_dir, args.directive_id, args.branch,
                args.identity_verified == "true", args.identity_reason, args.inspect_file,
            )
        else:
            result = validate_packet(args.root, args.packet_dir)
    except CanaryError as error:
        print(_canonical_json({"valid": False, "failureClass": error.failure_class}).decode("utf-8"), end="")
        return 2
    except Exception:
        print(_canonical_json({"valid": False, "failureClass": "internal-error"}).decode("utf-8"), end="")
        return 2
    print(_canonical_json(result).decode("utf-8"), end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
