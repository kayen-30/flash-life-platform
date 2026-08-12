@echo off
setlocal

set "MAVEN_HOME=%~dp0.tools\apache-maven-3.9.9"
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo Project-local Maven 3.9.9 was not found at "%MAVEN_HOME%".
    exit /b 1
)

call "%MAVEN_HOME%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
