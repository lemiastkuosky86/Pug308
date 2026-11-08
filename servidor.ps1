# Peugeot 308 - servidor local de atualizacoes
$ErrorActionPreference = 'Stop'
$Pasta = 'C:\Peugeot308Launcher\downloads'
$IP = '192.168.15.30'
$Porta = 8080

if (-not (Test-Path -LiteralPath $Pasta -PathType Container)) {
    New-Item -ItemType Directory -Path $Pasta -Force | Out-Null
}

$Raiz = [System.IO.Path]::GetFullPath($Pasta)
$RaizPrefixo = $Raiz.TrimEnd('\') + '\'
$Servidor = New-Object System.Net.HttpListener
$Servidor.Prefixes.Add("http://${IP}:${Porta}/")

try {
    $Servidor.Start()
    Write-Host "Servidor Peugeot 308 ONLINE: http://${IP}:${Porta}/" -ForegroundColor Green
    Write-Host "Arquivos: $Raiz"
    Write-Host 'Pressione Ctrl+C para encerrar.'

    while ($Servidor.IsListening) {
        $Contexto = $Servidor.GetContext()
        $Resposta = $Contexto.Response
        try {
            $Relativo = [Uri]::UnescapeDataString($Contexto.Request.Url.AbsolutePath.TrimStart('/'))
            if ([string]::IsNullOrWhiteSpace($Relativo)) {
                $Relativo = 'index.html'
            }

            $Arquivo = [System.IO.Path]::GetFullPath((Join-Path $Raiz $Relativo))
            $Ext = [System.IO.Path]::GetExtension($Arquivo).ToLowerInvariant()

            if (-not $Arquivo.StartsWith($RaizPrefixo, [System.StringComparison]::OrdinalIgnoreCase)) {
                $Resposta.StatusCode = 403
            }
            elseif ($Ext -notin @('.html', '.apk', '.json')) {
                $Resposta.StatusCode = 403
            }
            elseif (-not [System.IO.File]::Exists($Arquivo)) {
                $Resposta.StatusCode = 404
            }
            else {
                if ($Ext -eq '.json') {
                    $Resposta.ContentType = 'application/json; charset=utf-8'
                }
                elseif ($Ext -eq '.apk') {
                    $Resposta.ContentType = 'application/vnd.android.package-archive'
                    $Resposta.AddHeader('Content-Disposition', 'attachment; filename="app-debug.apk"')
                }
                else {
                    $Resposta.ContentType = 'text/html; charset=utf-8'
                }
                $Resposta.AddHeader('Cache-Control', 'no-store')
                $Stream = [System.IO.File]::OpenRead($Arquivo)
                try {
                    $Resposta.ContentLength64 = $Stream.Length
                    $Stream.CopyTo($Resposta.OutputStream)
                }
                finally {
                    $Stream.Dispose()
                }
                Write-Host "OK: $Relativo"
            }
        }
        catch {
            Write-Host "Erro: $($_.Exception.Message)" -ForegroundColor Red
            try { $Resposta.StatusCode = 500 } catch {}
        }
        finally {
            $Resposta.Close()
        }
    }
}
finally {
    if ($Servidor.IsListening) { $Servidor.Stop() }
    $Servidor.Close()
}
