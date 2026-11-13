# Builds the Google Play bundle: upload-key signed, without "talk from Phone B".
# Output: build\app\outputs\bundle\release\app-release.aab
$ErrorActionPreference = 'Stop'
Set-Location "$PSScriptRoot\.."
& C:\dev\flutter\bin\flutter.bat analyze
& C:\dev\flutter\bin\flutter.bat build appbundle --release -Pplay --dart-define=VOICE=false
