#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Apply build-fix actions to this repository. Idempotent and offline.
"""
import os, re, sys, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]

# Tunables
JAVA_RELEASE = os.environ.get("JAVA_RELEASE", "17")
LOMBOK_VERSION = os.environ.get("LOMBOK_VERSION", "1.18.30")
SPRING_BOOT_VERSION = os.environ.get("SPRING_BOOT_VERSION", "3.1.5")

def find_files(glob_patterns):
    for pat in glob_patterns:
        for p in ROOT.rglob(pat):
            if p.is_file():
                yield p

def read(p): 
    return p.read_text(encoding="utf-8", errors="ignore")

def write(p, s): 
    p.write_text(s, encoding="utf-8")

def ensure_line_in_block(text, block_name, line, open_tok="{", close_tok="}"):
    # naive insert into last occurrence of 'block_name { ... }'
    pattern = re.compile(rf'({block_name}\s*{re.escape(open_tok)})(.*?)(\s*{re.escape(close_tok)})', re.DOTALL)
    mlist = list(pattern.finditer(text))
    if not mlist:
        # create block at end
        add = f"\n{block_name} {open_tok}\n    {line}\n{close_tok}\n"
        return text + add, True
    m = mlist[-1]
    block = m.group(2)
    if line in block:
        return text, False
    new_block = block.rstrip() + f"\n    {line}\n"
    return text[:m.start(2)] + new_block + text[m.end(2):], True

def ensure_repo_maven_central_kts(text):
    return ensure_line_in_block(text, "repositories", "mavenCentral()")[0]

def ensure_java17_kts(text, release):
    if "sourceCompatibility" in text and f"VERSION_{release}" in text:
        return text
    java_block = f"""java {{
    sourceCompatibility = JavaVersion.VERSION_{release}
    targetCompatibility = JavaVersion.VERSION_{release}
}}"""
    if "java {" in text:
        # only append values if release not present
        if f"VERSION_{release}" in text:
            return text
        return text + "\n" + java_block + "\n"
    return text + "\n" + java_block + "\n"

def ensure_lombok_kts(text, version):
    needed = [
        f'compileOnly("org.projectlombok:lombok:{version}")',
        f'annotationProcessor("org.projectlombok:lombok:{version}")',
        f'testCompileOnly("org.projectlombok:lombok:{version}")',
        f'testAnnotationProcessor("org.projectlombok:lombok:{version}")',
    ]
    if all(n in text for n in needed):
        return text
    for n in needed:
        text, _ = ensure_line_in_block(text, "dependencies", n)
    return text

def ensure_spring_boot_plugin_kts(text, version):
    if 'id("org.springframework.boot")' in text:
        return text
    text, _ = ensure_line_in_block(text, "plugins", f'id("org.springframework.boot") version "{version}"')
    if 'id("io.spring.dependency-management")' not in text:
        text, _ = ensure_line_in_block(text, "plugins", 'id("io.spring.dependency-management") version "1.1.4"')
    return text

def ensure_encoding_kts(text):
    if "tasks.withType(JavaCompile::class.java)" in text and 'options.encoding = "UTF-8"' in text:
        return text
    snippet = """
tasks.withType(JavaCompile::class.java) {
    options.encoding = "UTF-8"
}
"""
    return text + "\n" + snippet + "\n"

def patch_gradle_kts_files():
    modified = []
    for path in find_files(["*.gradle.kts"]):
        txt = read(path)
        orig = txt
        txt = ensure_repo_maven_central_kts(txt)
        txt = ensure_java17_kts(txt, JAVA_RELEASE)
        txt = ensure_lombok_kts(txt, LOMBOK_VERSION)
        if path.name == "build.gradle.kts" or "app" in str(path.parent):
            txt = ensure_spring_boot_plugin_kts(txt, SPRING_BOOT_VERSION)
        txt = ensure_encoding_kts(txt)
        if txt != orig:
            write(path, txt)
            modified.append(str(path.relative_to(ROOT)))
    return modified

def ensure_gradlew_script():
    gradlew = ROOT / "gradlew"
    created = []
    if gradlew.exists():
        content = read(gradlew)
        if "exec ./gradlew-real" in content:
            real = ROOT / "gradlew-real"
            script = """#!/usr/bin/env sh
DIR="$(CDPATH= cd -- "$(dirname "$0")" && pwd)"
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA="${JAVA:-java}"
exec "$JAVA" -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
"""
            real.write_text(script, encoding="utf-8")
            os.chmod(real, 0o755)
            created.append(str(real.relative_to(ROOT)))
    return created

def main():
    modified = patch_gradle_kts_files()
    created = ensure_gradlew_script()
    if modified:
        print("[apply_matrix_fixes] Modified files:")
        for m in modified: print(" -", m)
    if created:
        print("[apply_matrix_fixes] Created:")
        for c in created: print(" -", c)
    print("[apply_matrix_fixes] Done")

if __name__ == "__main__":
    main()
