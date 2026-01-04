@ECHO OFF
SETLOCAL
SET DIR=%~dp0
SET WRAPPER_JAR=%DIR%gradle\wrapper\gradle-wrapper.jar
SET WRAPPER_MAIN=org.gradle.wrapper.GradleWrapperMain

IF EXIST "%WRAPPER_JAR%" (
  "%JAVA_HOME%\bin\java.exe" -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" %WRAPPER_MAIN% %*
  GOTO :EOF
)

WHERE gradle >NUL 2>&1
IF %ERRORLEVEL% EQU 0 (
  gradle %*
  GOTO :EOF
)

ECHO Gradle wrapper and system Gradle not found. Install Gradle or add gradle-wrapper.jar.
EXIT /B 1
