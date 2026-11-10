# Roda o conjunto-ouro contra a API usando um LLM real (openai | groq | gemini).
# A chave é pedida em modo oculto e fica só na memória deste processo: nada é gravado em arquivo.
#
#   powershell -ExecutionPolicy Bypass -File area1_ia\rodar_avaliacao_llm.ps1 -Provedor openai
param(
    [ValidateSet("openai", "groq", "gemini")][string]$Provedor = "openai",
    [int]$Porta = 8080
)

$raiz = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $raiz "area3_mvp\api\target\pertodemim-api-1.0.0.jar"
if (-not (Test-Path $jar)) { throw "Jar não encontrado. Rode 'mvn -DskipTests package' em area3_mvp\api." }

$var = @{ openai = "OPENAI_API_KEY"; groq = "GROQ_API_KEY"; gemini = "GEMINI_API_KEY" }[$Provedor]
$seguro = Read-Host "Cole a chave $Provedor (não aparece na tela)" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($seguro)
$chave = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
if ([string]::IsNullOrWhiteSpace($chave)) { throw "Chave vazia." }

Set-Item -Path "Env:$var" -Value $chave
$env:LLM_PROVIDER = $Provedor
$env:SERVER_PORT = "$Porta"
$api = $null
try {
    $api = Start-Process -FilePath "java" -ArgumentList @("-jar", $jar) -PassThru -WindowStyle Hidden
    $ok = $false
    for ($i = 0; $i -lt 60 -and -not $ok; $i++) {
        Start-Sleep -Seconds 1
        try { $ok = (Invoke-RestMethod "http://localhost:$Porta/actuator/health").status -eq "UP" } catch { }
    }
    if (-not $ok) { throw "API não subiu." }
    Push-Location $PSScriptRoot
    python avaliar_triagem.py --api "http://localhost:$Porta" --rotulo $Provedor
    $codigo = $LASTEXITCODE
    Pop-Location
    exit $codigo
}
finally {
    if ($api -and -not $api.HasExited) { Stop-Process -Id $api.Id -Force }
    Remove-Item "Env:$var" -ErrorAction SilentlyContinue
    Remove-Item Env:LLM_PROVIDER -ErrorAction SilentlyContinue
    Remove-Item Env:SERVER_PORT -ErrorAction SilentlyContinue
    $chave = $null
}
