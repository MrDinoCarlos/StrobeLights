param(
    [string[]] $Paths = @(
        "$PSScriptRoot\..\resource-pack\assets\strobelights\textures\item\flare_launcher.png",
        "$PSScriptRoot\..\resource-pack\assets\strobelights\textures\item\flare_core.png"
    )
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

foreach ($path in $Paths) {
    $resolved = (Resolve-Path -LiteralPath $path).Path
    $bitmap = [System.Drawing.Bitmap]::new($resolved)
    $changed = 0
    try {
        for ($y = 0; $y -lt $bitmap.Height; $y++) {
            for ($x = 0; $x -lt $bitmap.Width; $x++) {
                $pixel = $bitmap.GetPixel($x, $y)
                if ($pixel.A -ge 22 -and $pixel.A -le 26) {
                    $bitmap.SetPixel(
                        $x,
                        $y,
                        [System.Drawing.Color]::FromArgb(27, $pixel.R, $pixel.G, $pixel.B)
                    )
                    $changed++
                }
            }
        }
        $temporary = $resolved + '.sanitized.png'
        $bitmap.Save($temporary, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $bitmap.Dispose()
    }
    [System.IO.File]::Copy($temporary, $resolved, $true)
    [System.IO.File]::Delete($temporary)
    Write-Output "${resolved}: moved $changed pixels outside the reserved marker alpha band"
}
