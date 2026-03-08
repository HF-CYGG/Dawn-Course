<# 
  功能：智能代理的 Git 推送/拉取脚本（支持代理不可用时自动回退为直连）
  使用说明（在项目根目录运行 PowerShell）：
    - 推送主仓库：   .\scripts\git-smart-proxy.ps1 push main
    - 拉取主仓库：   .\scripts\git-smart-proxy.ps1 pull main
    - 推送子仓库：   .\scripts\git-smart-proxy.ps1 push server
    - 拉取子仓库：   .\scripts\git-smart-proxy.ps1 pull server
    - 推送全部仓库： .\scripts\git-smart-proxy.ps1 push-all
    - 拉取全部仓库： .\scripts\git-smart-proxy.ps1 pull-all
  
  代理来源优先级（自动检测）：
    1) 环境变量：HTTP_PROXY / HTTPS_PROXY（User/Machine）
    2) WinHTTP 系统代理（netsh winhttp show proxy）
    3) 无代理（直连）

  行为逻辑：
    - 优先使用检测到的代理执行 git 操作；
    - 如果失败（退出码非 0），自动回退为直连再尝试一次；
    - 输出清晰的执行过程与最终结果。
#>

param(
  [ValidateSet('push','pull','push-all','pull-all')]
  [string]$Action = 'push',
  [ValidateSet('main','server')]
  [string]$Target = 'main'
)

function Get-ProjectRoot {
  # 获取项目根目录（脚本位于 scripts/ 下）
  (Get-Item $PSScriptRoot).Parent.FullName
}

function Get-RepoPath($target) {
  $root = Get-ProjectRoot
  if ($target -eq 'server') { return Join-Path $root 'server' }
  return $root
}

function Get-SystemProxy {
  # 读取环境变量中的代理（优先）
  $envHttp = [Environment]::GetEnvironmentVariable('HTTP_PROXY','User')
  if (-not $envHttp) { $envHttp = [Environment]::GetEnvironmentVariable('HTTP_PROXY','Machine') }
  $envHttps = [Environment]::GetEnvironmentVariable('HTTPS_PROXY','User')
  if (-not $envHttps) { $envHttps = [Environment]::GetEnvironmentVariable('HTTPS_PROXY','Machine') }

  if ($envHttp -or $envHttps) {
    return @{
      http = $envHttp
      https = $envHttps
      source = 'ENV'
    }
  }

  # 回退读取 WinHTTP 代理
  $p = netsh winhttp show proxy | Out-String
  if ($p -match '直接访问' -or $p -match 'Direct access') {
    return @{ http = $null; https = $null; source = 'WINHTTP' }
  }
  $http=''
  $https=''
  if ($p -match 'http=([^;\s]+)') { $http=$Matches[1] }
  if ($p -match 'https=([^;\s]+)') { $https=$Matches[1] }
  if (-not $http -and -not $https -and $p -match 'Proxy Server\(s\)\s*:\s*([^\r\n]+)') {
    $common=$Matches[1]
    $http=$common
    $https=$common
  }
  return @{ http = $http; https = $https; source = 'WINHTTP' }
}

function Normalize-ProxyUrl($raw) {
  # 规范化代理地址（如果无协议前缀，默认 http://）
  if (-not $raw) { return $null }
  if ($raw -match '^(http|https|socks|socks5|socks5h)://') { return $raw }
  return "http://$raw"
}

function Test-Remote($repoPath, $proxyUrl) {
  # 通过 ls-remote 测试与 origin 的连通性
  if ($proxyUrl) {
    git -C $repoPath -c http.proxy=$proxyUrl -c https.proxy=$proxyUrl ls-remote origin --heads *> $null
  } else {
    git -C $repoPath -c http.proxy= -c https.proxy= ls-remote origin --heads *> $null
  }
  return $LASTEXITCODE
}

function Invoke-Git($repoPath, $gitArgs, $proxyUrl) {
  # 使用代理执行 Git 命令；失败则回退直连
  if ($proxyUrl) {
    Write-Host "使用代理 $proxyUrl 执行：git -C `"$repoPath`" $gitArgs"
    git -C $repoPath -c http.proxy=$proxyUrl -c https.proxy=$proxyUrl $gitArgs
    $code = $LASTEXITCODE
    if ($code -ne 0) {
      Write-Warning "代理连接失败（退出码 $code），尝试直连重试……"
      git -C $repoPath -c http.proxy= -c https.proxy= $gitArgs
      return $LASTEXITCODE
    }
    return $code
  } else {
    Write-Host "未检测到代理，使用直连执行：git -C `"$repoPath`" $gitArgs"
    git -C $repoPath -c http.proxy= -c https.proxy= $gitArgs
    return $LASTEXITCODE
  }
}

function Do-Action($repoPath, $action, $proxyUrl) {
  switch ($action) {
    'push' { return Invoke-Git $repoPath 'push' $proxyUrl }
    'pull' { return Invoke-Git $repoPath 'pull' $proxyUrl }
    default { throw "不支持的操作：$action" }
  }
}

# 主流程
$repoMain   = Get-RepoPath 'main'
$repoServer = Get-RepoPath 'server'
$proxyInfo  = Get-SystemProxy
$proxyRaw   = $proxyInfo.http ? $proxyInfo.http : $proxyInfo.https
$proxyUrl   = Normalize-ProxyUrl $proxyRaw

Write-Host "代理来源：$($proxyInfo.source)；代理地址：$proxyUrl"

switch ($Action) {
  'push-all' {
    $code1 = Do-Action $repoMain   'push' $proxyUrl
    $code2 = Do-Action $repoServer 'push' $proxyUrl
    if ($code1 -eq 0 -and $code2 -eq 0) { exit 0 } else { exit 1 }
  }
  'pull-all' {
    $code1 = Do-Action $repoMain   'pull' $proxyUrl
    $code2 = Do-Action $repoServer 'pull' $proxyUrl
    if ($code1 -eq 0 -and $code2 -eq 0) { exit 0 } else { exit 1 }
  }
  default {
    $repo = Get-RepoPath $Target
    $code = Do-Action $repo $Action $proxyUrl
    exit $code
  }
}
