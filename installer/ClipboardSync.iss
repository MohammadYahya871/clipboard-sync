#define MyAppName "Clipboard Sync"
#define MyAppExeName "ClipboardSync.App.exe"
#define MyAppPublisher "Clipboard Sync"
#define MyAppUrl "https://github.com/clipboard-sync/clipboard-sync"
#define MyAppMutex "Local\ClipboardSync.App.Singleton"

#ifndef MyAppVersion
  #define MyAppVersion "1.0.0"
#endif

#ifndef SourceDir
  #define SourceDir "..\dist\windows\ClipboardSync-windows-x64"
#endif

#ifndef OutputDir
  #define OutputDir "..\dist\release\v" + MyAppVersion
#endif

[Setup]
AppId={{A7C4E2B1-9F3D-4A6E-8C5B-1D2E3F4A5B6C}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}
AppPublisherURL={#MyAppUrl}
AppSupportURL={#MyAppUrl}
AppUpdatesURL={#MyAppUrl}
DefaultDirName={autopf}\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename=ClipboardSync-{#MyAppVersion}-windows-x64-setup
SetupIconFile={#SourceDir}\Assets\AppIcon.ico
UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName}
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0
AppMutex={#MyAppMutex}
CloseApplications=force
CloseApplicationsFilter={#MyAppExeName}
UsePreviousAppDir=yes
DisableDirPage=auto
DisableReadyPage=no
ShowLanguageDialog=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked
Name: "startup"; Description: "Start {#MyAppName} when Windows starts"; GroupDescription: "Additional options:"; Flags: checkedonce

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{group}\Uninstall {#MyAppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Registry]
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "ClipboardSync"; ValueData: """{app}\{#MyAppExeName}"""; Tasks: startup; Flags: uninsdeletevalue

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "Launch {#MyAppName}"; Flags: nowait postinstall skipifsilent

[UninstallDelete]
Type: filesandordirs; Name: "{app}"

[Code]
function InitializeSetup(): Boolean;
begin
  Result := True;
end;

function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
end;
