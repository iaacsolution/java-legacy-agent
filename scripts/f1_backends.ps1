<#
.SYNOPSIS
    Mesure le F1 du golden dataset sur le backend Claude Haiku, en plusieurs passages.

.DESCRIPTION
    Complète le tableau F1 côté cloud, qui n'a pas pu être mesuré au moment de
    l'instrumentation du flight recorder : la clé ANTHROPIC_API_KEY du .env était
    rejetée par l'API (HTTP 401, « API key is invalid », vérifié en appel direct).

    Manipulation de la clé — trois règles tenues par ce script :
      - la clé est lue UNIQUEMENT depuis la variable d'environnement ANTHROPIC_API_KEY ;
        elle n'est jamais lue depuis .env, jamais demandée en argument ;
      - elle n'est écrite dans aucun fichier et dans aucun journal : les sorties des
        passages sont filtrées avant d'être écrites sur disque ;
      - ALLOW_CLOUD_CODE_ANALYSIS=true n'est positionné que pour le processus de mesure,
        via son environnement propre. La valeur du dépôt (false) n'est pas touchée.

    Le dataset utilisé est synthétique (golden_dataset_3cases.json) : aucun code client
    ne sort du périmètre.

.PARAMETER Runs
    Nombre de passages. Défaut : 3, pour pouvoir donner médiane / min / max.

.PARAMETER Dataset
    Dataset à évaluer. Défaut : golden_dataset_3cases.json (les 3 cas historiques,
    ceux du chiffre 0,757).

.EXAMPLE
    Definir la variable d'environnement ANTHROPIC_API_KEY, puis :
    .\scripts\f1_backends.ps1

.EXAMPLE
    .\scripts\f1_backends.ps1 -Runs 5 -Dataset golden_dataset.json
#>

[CmdletBinding()]
param(
    [int]    $Runs    = 3,
    [string] $Dataset = "golden_dataset_3cases.json"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$jar      = Join-Path $repoRoot "agent\target\java-legacy-agent-1.0.0.jar"
$outDir   = Join-Path $repoRoot "logs"

# ── Préconditions ───────────────────────────────────────────────────────────
$apiKey = $env:ANTHROPIC_API_KEY
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Write-Error ([string]::Join([Environment]::NewLine, @(
        "ANTHROPIC_API_KEY n'est pas definie dans l'environnement.",
        "",
        "Ce script ne lit deliberement pas la cle depuis .env : elle doit etre",
        "fournie pour la duree de la session seulement.",
        "",
        "    Definir ANTHROPIC_API_KEY dans l'environnement de la session."
    )))
    exit 1
}

if (-not (Test-Path $jar)) {
    Write-Error "Jar introuvable : $jar`nLancer d'abord : mvn -f agent/pom.xml package -DskipTests"
    exit 1
}

$datasetPath = Join-Path $repoRoot $Dataset
if (-not (Test-Path $datasetPath)) {
    Write-Error "Dataset introuvable : $datasetPath"
    exit 1
}

if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

# ── Garde-fou : ne jamais laisser la clé atteindre un fichier ───────────────
# Même si la clé n'est pas censée apparaître dans la sortie, on filtre par
# précaution plutôt que de faire confiance à ce que le programme imprime.
function Remove-Secrets {
    param([string] $Line, [string] $Secret)
    if ([string]::IsNullOrEmpty($Line)) { return $Line }
    $masked = $Line.Replace($Secret, "<CLE-MASQUEE>")
    return ($masked -replace 'sk-ant-[A-Za-z0-9_\-]+', '<CLE-MASQUEE>')
}

# ── Passages ────────────────────────────────────────────────────────────────
Write-Host ("=" * 70)
Write-Host "  F1 golden dataset — backend Claude Haiku"
Write-Host "  Dataset : $Dataset"
Write-Host "  Passages: $Runs"
Write-Host ("=" * 70)

$scores = @()

for ($i = 1; $i -le $Runs; $i++) {
    Write-Host "`n--- passage $i / $Runs ---"

    $stamp   = Get-Date -Format "yyyyMMdd_HHmmss"
    $logFile = Join-Path $outDir "f1_haiku_${stamp}_run$i.log"

    # ALLOW_CLOUD_CODE_ANALYSIS et la clé ne sont posés que pour CE processus.
    # Start-Process n'est pas utilisé : on veut la sortie en flux pour la filtrer.
    $previousKey   = $env:ANTHROPIC_API_KEY
    $previousAllow = $env:ALLOW_CLOUD_CODE_ANALYSIS
    $previousFlight = $env:FLIGHTREC_ENABLED
    try {
        $env:ALLOW_CLOUD_CODE_ANALYSIS = "true"
        $env:FLIGHTREC_ENABLED         = "false"   # mesure du F1, pas de l'instrumentation

        $sortie = & java "-Dstdout.encoding=UTF-8" -cp $jar `
                    "com.audensiel.legacy.agent.EvalMain" $datasetPath 2>&1 |
                  ForEach-Object { Remove-Secrets -Line ([string]$_) -Secret $apiKey }
    }
    finally {
        $env:ALLOW_CLOUD_CODE_ANALYSIS = $previousAllow
        $env:FLIGHTREC_ENABLED         = $previousFlight
        $env:ANTHROPIC_API_KEY         = $previousKey
    }

    $sortie | Out-File -FilePath $logFile -Encoding utf8

    $backendOk = $sortie | Select-String -SimpleMatch "Anthropic Claude Haiku"
    if (-not $backendOk) {
        Write-Warning "Le backend actif n'est PAS Claude Haiku — passage $i non comptabilisé."
        Write-Warning "Vérifier la clé et ALLOW_CLOUD_CODE_ANALYSIS. Journal : $logFile"
        continue
    }

    $ligne = $sortie | Select-String -Pattern "F1 GLOBAL" | Select-Object -Last 1
    if ($ligne -and ($ligne.Line -match "([0-9]+[.,][0-9]+)\s*$")) {
        # Locale fr : la sortie Java peut utiliser la virgule décimale.
        $valeur = [double]($Matches[1].Replace(",", "."))
        $scores += $valeur
        Write-Host ("  F1 global : {0:N3}   (journal : {1})" -f $valeur, $logFile)
    } else {
        Write-Warning "F1 global illisible au passage $i. Journal : $logFile"
    }
}

# ── Synthèse ────────────────────────────────────────────────────────────────
Write-Host "`n$("=" * 70)"
if ($scores.Count -eq 0) {
    Write-Error "Aucun passage exploitable — rien à rapporter."
    exit 1
}

$tri     = $scores | Sort-Object
$min     = $tri[0]
$max     = $tri[-1]
$mediane = if ($tri.Count % 2 -eq 1) {
    $tri[[int][Math]::Floor($tri.Count / 2)]
} else {
    ($tri[$tri.Count / 2 - 1] + $tri[$tri.Count / 2]) / 2
}

Write-Host "  RÉSULTAT — backend Claude Haiku, $($scores.Count) passage(s) exploitable(s)"
Write-Host ("  valeurs : {0}" -f (($scores | ForEach-Object { "{0:N3}" -f $_ }) -join ", "))
Write-Host ("  médiane : {0:N3}" -f $mediane)
Write-Host ("  minimum : {0:N3}" -f $min)
Write-Host ("  maximum : {0:N3}" -f $max)
Write-Host ("=" * 70)
Write-Host ""
Write-Host "Rappel avant de citer ce chiffre :"
Write-Host "  - seule la composante DependencyMapper (regex, sans LLM) est reproductible"
Write-Host "    au bit pres ; les composantes LLM derivent d'un passage a l'autre meme a"
Write-Host "    temperature 0,1 ;"
Write-Host "  - toujours preciser le backend en rapportant un F1 : les valeurs Ollama et"
Write-Host "    Claude Haiku ne sont pas comparables entre elles."
