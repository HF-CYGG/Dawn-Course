#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,

    [string]$AndroidSdkPath,

    [string[]]$RequiredAbis = @(
        "arm64-v8a",
        "armeabi-v7a",
        "x86",
        "x86_64"
    ),

    [string[]]$RequiredLibraries = @(
        "libsqlcipher.so",
        "libquickjs-android-wrapper.so"
    )
)

$ErrorActionPreference = "Stop"
$minimumAlignment = 0x4000L

function Resolve-AndroidSdkPath {
    param([string]$ExplicitPath)

    if (-not [string]::IsNullOrWhiteSpace($ExplicitPath)) {
        return (Resolve-Path -LiteralPath $ExplicitPath).Path
    }

    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    $localProperties = Join-Path $PSScriptRoot "..\..\local.properties"
    if (Test-Path -LiteralPath $localProperties) {
        $sdkLine = Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^sdk\.dir=' } |
            Select-Object -First 1
        if ($sdkLine) {
            $configuredPath = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'
            if (Test-Path -LiteralPath $configuredPath) {
                return (Resolve-Path -LiteralPath $configuredPath).Path
            }
        }
    }

    throw "Android SDK 未找到；请传入 -AndroidSdkPath 或配置 ANDROID_HOME/ANDROID_SDK_ROOT。"
}

function Find-LatestTool {
    param(
        [string]$Root,
        [string[]]$RelativePaths
    )

    $tool = Get-ChildItem -LiteralPath $Root -Directory |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
        ForEach-Object {
            $versionRoot = $_.FullName
            $RelativePaths | ForEach-Object { Join-Path $versionRoot $_ }
        } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1

    if (-not $tool) {
        throw "工具未找到：$Root\*\($($RelativePaths -join ' | '))"
    }
    return $tool
}

function Find-LatestNdkReadElf {
    param([string]$NdkRoot)

    $toolNames = @("llvm-readelf.exe", "llvm-readelf")
    $tool = Get-ChildItem -LiteralPath $NdkRoot -Directory |
        Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
        ForEach-Object {
            $prebuiltRoot = Join-Path $_.FullName "toolchains/llvm/prebuilt"
            if (Test-Path -LiteralPath $prebuiltRoot) {
                Get-ChildItem -LiteralPath $prebuiltRoot -Directory | ForEach-Object {
                    $hostRoot = $_.FullName
                    $toolNames | ForEach-Object { Join-Path $hostRoot "bin/$_" }
                }
            }
        } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1

    if (-not $tool) {
        throw "llvm-readelf 未找到：$NdkRoot\*\toolchains\llvm\prebuilt\*\bin"
    }
    return $tool
}

$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$sdkPath = Resolve-AndroidSdkPath -ExplicitPath $AndroidSdkPath
$zipalign = Find-LatestTool `
    -Root (Join-Path $sdkPath "build-tools") `
    -RelativePaths @("zipalign.exe", "zipalign")
$readelf = Find-LatestNdkReadElf -NdkRoot (Join-Path $sdkPath "ndk")

& $zipalign -c -P 16 4 $resolvedApk
if ($LASTEXITCODE -ne 0) {
    throw "APK 的 16 KB ZIP 对齐校验失败：$resolvedApk"
}

$temporaryRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("dawn-native-page-size-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
$resolvedTemporaryRoot = (Resolve-Path -LiteralPath $temporaryRoot).Path
$resolvedSystemTemp = (Resolve-Path -LiteralPath ([System.IO.Path]::GetTempPath())).Path
if (-not $resolvedTemporaryRoot.StartsWith($resolvedSystemTemp, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "拒绝使用系统临时目录之外的清理目标：$resolvedTemporaryRoot"
}

try {
    # AGP may legally emit duplicate non-native ZIP entries; extracting the whole APK would fail
    # before this gate reaches the native payload. Extract only lib/**/*.so and reject traversal.
    $apk = [System.IO.Compression.ZipFile]::OpenRead($resolvedApk)
    try {
        $nativeEntries = [System.Collections.Generic.HashSet[string]]::new(
            [System.StringComparer]::Ordinal
        )
        foreach ($entry in $apk.Entries) {
            $entryPath = $entry.FullName.Replace('\', '/')
            if ($entryPath -notmatch '^lib/[^/]+/[^/]+\.so$') { continue }
            if ($entryPath.Contains('../') -or $entryPath.StartsWith('/')) {
                throw "APK native entry 路径不安全：$entryPath"
            }
            if (-not $nativeEntries.Add($entryPath)) {
                throw "APK 包含重复 native entry，无法确定运行时实际装载对象：$entryPath"
            }
            $destination = Join-Path $resolvedTemporaryRoot ($entryPath.Replace('/', [IO.Path]::DirectorySeparatorChar))
            $resolvedParent = Split-Path $destination -Parent
            New-Item -ItemType Directory -Path $resolvedParent -Force | Out-Null
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $destination, $true)
        }
    }
    finally {
        $apk.Dispose()
    }
    $nativeRoot = Join-Path $resolvedTemporaryRoot "lib"
    if (-not (Test-Path -LiteralPath $nativeRoot)) {
        throw "APK 不包含任何 native library：$resolvedApk"
    }

    $actualAbis = Get-ChildItem -LiteralPath $nativeRoot -Directory | Select-Object -ExpandProperty Name
    $missingAbis = $RequiredAbis | Where-Object { $_ -notin $actualAbis }
    if ($missingAbis.Count -gt 0) {
        throw "APK 缺少要求的 ABI：$($missingAbis -join ', ')"
    }

    $missingLibraries = [System.Collections.Generic.List[string]]::new()
    foreach ($abi in $RequiredAbis) {
        foreach ($libraryName in $RequiredLibraries) {
            $libraryPath = Join-Path (Join-Path $nativeRoot $abi) $libraryName
            if (-not (Test-Path -LiteralPath $libraryPath -PathType Leaf)) {
                $missingLibraries.Add("$abi/$libraryName")
            }
        }
    }
    if ($missingLibraries.Count -gt 0) {
        throw "APK 缺少要求的 ABI/原生库组合：$($missingLibraries -join ', ')"
    }

    $failures = [System.Collections.Generic.List[string]]::new()
    $libraries = Get-ChildItem -LiteralPath $nativeRoot -Recurse -Filter "*.so"
    foreach ($library in $libraries) {
        $programHeaders = & $readelf -lW $library.FullName
        if ($LASTEXITCODE -ne 0) {
            $failures.Add("$($library.FullName)：llvm-readelf 执行失败")
            continue
        }

        $loadAlignments = $programHeaders |
            Select-String '^\s*LOAD' |
            ForEach-Object { ($_ -split '\s+')[-1] } |
            Sort-Object -Unique

        if ($loadAlignments.Count -eq 0) {
            $failures.Add("$($library.FullName)：未找到 LOAD program header")
            continue
        }

        foreach ($alignmentText in $loadAlignments) {
            $alignment = [Convert]::ToInt64(($alignmentText -replace '^0x', ''), 16)
            $relativePath = $library.FullName.Substring($resolvedTemporaryRoot.Length + 1)
            Write-Output ("{0}: {1}" -f $relativePath, $alignmentText)
            if ($alignment -lt $minimumAlignment) {
                $failures.Add("$relativePath 的 LOAD 对齐为 $alignmentText，低于 0x4000")
            }
        }
    }

    if ($failures.Count -gt 0) {
        throw ("ELF 16 KB 对齐校验失败：`n- " + ($failures -join "`n- "))
    }

    Write-Output "Native page-size gate passed: ZIP alignment、要求的 ABI/库矩阵与全部 ELF LOAD 均满足 16 KB。"
}
finally {
    if (Test-Path -LiteralPath $resolvedTemporaryRoot) {
        Remove-Item -LiteralPath $resolvedTemporaryRoot -Recurse -Force
    }
}
