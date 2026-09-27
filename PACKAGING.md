# Windows installer

Build a self-contained Windows installer executable with:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\package-windows.ps1 -JdkHome $env:JAVA_HOME
```

Use the path to your own full JDK 17+ if it differs. The build also requires an internet
connection to download the official WiX Toolset 3 package from NuGet on the first run.
The package SHA-512 is checked and its tools are extracted locally; no machine-wide WiX
installation is needed. Use `-WiXHome` to select an existing WiX tools directory. The script creates
`target\installer\GitPilot-1.1.0.exe` and `target\installer\GitPilot-1.1.0.msi`; override the
version with `.\package-windows.ps1 -AppVersion <version>`.

The installer bundles the Java runtime and application dependencies, so users do not need
to install Java separately. JGit is an automatic module, so packaging uses the classpath
launcher rather than a trimmed `jlink` runtime image. The JavaFX window uses
`src\main\resources\com\git\client\gitdesk_icon.png`; the packaging script converts that
same image to a multi-resolution Windows `.ico` for the executable, installer, and shortcuts.
At the end of installation, the installer offers to launch GitPilot; the option is enabled
by default and can be unchecked.
