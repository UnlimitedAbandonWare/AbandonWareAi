@ECHO OFF
SETLOCAL
SET DIR=%~dp0
SET WRAPPER_JAR=%DIR%gradle\wrapper\gradle-wrapper.jar
SET WRAPPER_MAIN=org.gradle.wrapper.GradleWrapperMain

IF NOT EXIST "%WRAPPER_JAR%" GOTO USE_SYSTEM_GRADLE

"%JAVA_HOME%\bin\java.exe" -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" %WRAPPER_MAIN% %*
EXIT /B %ERRORLEVEL%

:USE_SYSTEM_GRADLE
WHERE gradle >NUL 2>&1
IF ERRORLEVEL 1 GOTO GRADLE_NOT_FOUND

gradle %*
EXIT /B %ERRORLEVEL%

:GRADLE_NOT_FOUND
ECHO Gradle wrapper and system Gradle not found. Install Gradle or add gradle-wrapper.jar.
EXIT /B 1
