param(
    [ValidateSet('Debug','Release')] [string]$Configuration = 'Debug',
    [switch]$SkipChecks,
    [switch]$WindowsJdkWorkaround
)
$ErrorActionPreference = 'Stop'
Push-Location $PSScriptRoot
try {
    if (!$env:JAVA_HOME) { throw '请设置 JAVA_HOME 为 JDK 21 的目录。' }
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    $sdkPath = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if ($sdkPath) {
        $escapedSdk = $sdkPath.Replace('\','/').Replace(':','\:')
        [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'local.properties'), "sdk.dir=$escapedSdk`n")
    }
    if (!(Test-Path 'local.properties')) { throw '请设置 ANDROID_HOME 或用 Android Studio 选择 SDK。' }
    if ($Configuration -eq 'Release' -and !$env:JIAYIN_KEYSTORE_PATH) { throw '发布构建需要先设置 JIAYIN_* 签名环境变量，详见 JIAYIN.md。' }
    $gradleArgs = @("assembleDegoogled$Configuration", '--console=plain')
    if (!$SkipChecks) { $gradleArgs += @('testDegoogledDebugUnitTest','testTempusDebugUnitTest','lintDegoogledRelease') }
    if ($WindowsJdkWorkaround) {
        if (!$sdkPath) { throw 'Windows workaround 需要 ANDROID_HOME。' }
        & python tools/prepare-windows-jdk-image.py --jdk $env:JAVA_HOME --sdk $sdkPath
        if ($LASTEXITCODE -ne 0) { throw 'Android JDK image 生成失败。' }
        $imagePath = (Resolve-Path 'local-toolchains/android-jdk-image').Path
        $gradleArgs += @('-I', (Join-Path $PSScriptRoot 'tools/local-jdk-image.init.gradle'), "-Pjiayin.jdkImage.path=$imagePath")
    }
    $gradleCommand = if ($env:GRADLE_HOME -and (Test-Path (Join-Path $env:GRADLE_HOME 'bin/gradle.bat'))) { Join-Path $env:GRADLE_HOME 'bin/gradle.bat' } else { Join-Path $PSScriptRoot 'gradlew.bat' }
    & $gradleCommand @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw '构建或检查失败，详见上方输出。' }
} finally { Pop-Location }
