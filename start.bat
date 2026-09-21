@echo off

set JPATH=C:\Program Files\Eclipse Adoptium\jdk-21.0.8.9-hotspot\bin\
"%JPATH%java.exe" -version

echo.

echo Compiling RepDev...
"%JPATH%javac.exe" -encoding Cp1252 -cp "swt-win/swt.jar;swtcompare.jar;jsch.jar" -d . com\repdev\*.java com\repdev\parser\*.java com\repdev\launcher\*.java
if NOT %ERRORLEVEL% == 0 pause & exit /b 1

"%JPATH%java.exe" -cp swt-win/swt.jar;swtcompare.jar;jsch.jar;./ -Djava.library.path=swt-win -Dfile.encoding=Cp1252 -DtestDev=true com.repdev.RepDevMain

if NOT %ERRORLEVEL% == 0 pause