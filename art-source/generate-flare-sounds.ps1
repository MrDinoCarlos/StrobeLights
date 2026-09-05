param(
    [Parameter(Mandatory = $true)]
    [string] $Ffmpeg,
    [string] $OutputDir = "$PSScriptRoot\..\resource-pack\assets\strobelights\sounds"
)

$ErrorActionPreference = 'Stop'

# Every waveform below is synthesized from tones and deterministic noise. No
# recording or sample from the reference video is copied into the resource pack.
Add-Type -TypeDefinition @'
using System;
using System.IO;

public sealed class FlareSoundBuilder
{
    private const int SampleRate = 48000;
    private readonly double[] samples;
    private readonly Random random;

    public FlareSoundBuilder(double durationSeconds, int seed)
    {
        samples = new double[(int)Math.Ceiling(durationSeconds * SampleRate)];
        random = new Random(seed);
    }

    public void AddTone(
        double start,
        double duration,
        double startFrequency,
        double endFrequency,
        double amplitude,
        double attack,
        double decayPower)
    {
        int first = Math.Max(0, (int)(start * SampleRate));
        int count = Math.Min(samples.Length - first, (int)(duration * SampleRate));
        double phase = 0.0;
        for (int i = 0; i < count; i++)
        {
            double progress = i / (double)Math.Max(1, count - 1);
            double frequency = startFrequency + (endFrequency - startFrequency) * progress;
            phase += Math.PI * 2.0 * frequency / SampleRate;
            double attackGain = attack <= 0.0 ? 1.0 : Math.Min(1.0, i / (attack * SampleRate));
            double envelope = attackGain * Math.Pow(Math.Max(0.0, 1.0 - progress), decayPower);
            samples[first + i] += Math.Sin(phase) * amplitude * envelope;
        }
    }

    public void AddNoise(
        double start,
        double duration,
        double amplitude,
        double attack,
        double decayPower,
        double highPass,
        double lowPass)
    {
        int first = Math.Max(0, (int)(start * SampleRate));
        int count = Math.Min(samples.Length - first, (int)(duration * SampleRate));
        double highState = 0.0;
        double bandState = 0.0;
        double highAlpha = 1.0 - Math.Exp(-2.0 * Math.PI * highPass / SampleRate);
        double lowAlpha = 1.0 - Math.Exp(-2.0 * Math.PI * lowPass / SampleRate);
        for (int i = 0; i < count; i++)
        {
            double raw = random.NextDouble() * 2.0 - 1.0;
            highState += highAlpha * (raw - highState);
            double high = raw - highState;
            bandState += lowAlpha * (high - bandState);
            double progress = i / (double)Math.Max(1, count - 1);
            double attackGain = attack <= 0.0 ? 1.0 : Math.Min(1.0, i / (attack * SampleRate));
            double envelope = attackGain * Math.Pow(Math.Max(0.0, 1.0 - progress), decayPower);
            samples[first + i] += bandState * amplitude * envelope;
        }
    }

    public void AddCrackles(double start, double duration, int count, double amplitude)
    {
        for (int eventIndex = 0; eventIndex < count; eventIndex++)
        {
            double eventStart = start + random.NextDouble() * duration;
            double eventDuration = 0.004 + random.NextDouble() * 0.016;
            AddNoise(eventStart, eventDuration, amplitude, 0.0005, 2.5, 900.0, 11000.0);
            AddTone(
                eventStart,
                eventDuration * 1.8,
                1700.0 + random.NextDouble() * 2100.0,
                700.0 + random.NextDouble() * 900.0,
                amplitude * 0.3,
                0.0005,
                3.0
            );
        }
    }

    public void WriteWave(string path)
    {
        double peak = 0.0001;
        for (int i = 0; i < samples.Length; i++)
            peak = Math.Max(peak, Math.Abs(samples[i]));
        double gain = 0.92 / peak;

        using (var writer = new BinaryWriter(File.Create(path)))
        {
            int dataLength = samples.Length * 2;
            writer.Write(new char[] {'R', 'I', 'F', 'F'});
            writer.Write(36 + dataLength);
            writer.Write(new char[] {'W', 'A', 'V', 'E'});
            writer.Write(new char[] {'f', 'm', 't', ' '});
            writer.Write(16);
            writer.Write((short)1);
            writer.Write((short)1);
            writer.Write(SampleRate);
            writer.Write(SampleRate * 2);
            writer.Write((short)2);
            writer.Write((short)16);
            writer.Write(new char[] {'d', 'a', 't', 'a'});
            writer.Write(dataLength);
            for (int i = 0; i < samples.Length; i++)
            {
                double shaped = Math.Tanh(samples[i] * gain * 1.35) / Math.Tanh(1.35);
                writer.Write((short)Math.Round(Math.Max(-1.0, Math.Min(1.0, shaped)) * 32767.0));
            }
        }
    }
}
'@

$temporaryDir = Join-Path ([System.IO.Path]::GetTempPath()) ('strobelights-flare-audio-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $temporaryDir | Out-Null
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

function Export-FlareSound([string] $Name, [FlareSoundBuilder] $Builder) {
    $wavePath = Join-Path $temporaryDir ($Name + '.wav')
    $oggPath = Join-Path $OutputDir ($Name + '.ogg')
    $Builder.WriteWave($wavePath)
    & $Ffmpeg -hide_banner -loglevel error -y -i $wavePath -ac 1 -ar 48000 -c:a libvorbis -q:a 7 $oggPath
    if ($LASTEXITCODE -ne 0) {
        throw "FFmpeg failed while encoding $Name"
    }
}

try {
    $open = [FlareSoundBuilder]::new(0.42, 691201)
    $open.AddNoise(0.01, 0.10, 0.70, 0.001, 3.2, 500.0, 9000.0)
    $open.AddTone(0.015, 0.30, 1750.0, 1180.0, 0.36, 0.001, 3.6)
    $open.AddTone(0.025, 0.24, 2550.0, 2150.0, 0.22, 0.001, 4.0)
    $open.AddTone(0.16, 0.20, 310.0, 190.0, 0.42, 0.001, 4.0)
    $open.AddCrackles(0.12, 0.15, 3, 0.34)
    Export-FlareSound 'flare_reload_open' $open

    $insert = [FlareSoundBuilder]::new(0.48, 691202)
    $insert.AddNoise(0.00, 0.27, 0.34, 0.03, 1.5, 650.0, 5200.0)
    $insert.AddTone(0.17, 0.24, 240.0, 125.0, 0.62, 0.001, 3.8)
    $insert.AddNoise(0.18, 0.11, 0.58, 0.001, 3.0, 240.0, 6100.0)
    $insert.AddTone(0.205, 0.25, 1120.0, 780.0, 0.24, 0.001, 4.2)
    $insert.AddCrackles(0.24, 0.10, 2, 0.24)
    Export-FlareSound 'flare_reload_insert' $insert

    $close = [FlareSoundBuilder]::new(0.42, 691203)
    $close.AddNoise(0.01, 0.13, 0.82, 0.001, 4.0, 300.0, 10000.0)
    $close.AddTone(0.012, 0.31, 920.0, 520.0, 0.55, 0.001, 4.4)
    $close.AddTone(0.018, 0.24, 1880.0, 1380.0, 0.27, 0.001, 4.8)
    $close.AddTone(0.21, 0.17, 2700.0, 1700.0, 0.22, 0.001, 5.0)
    $close.AddCrackles(0.18, 0.08, 2, 0.30)
    Export-FlareSound 'flare_reload_close' $close

    $fire = [FlareSoundBuilder]::new(1.18, 691204)
    $fire.AddNoise(0.00, 0.20, 1.00, 0.0005, 4.0, 35.0, 12000.0)
    $fire.AddTone(0.00, 0.48, 105.0, 48.0, 0.90, 0.001, 3.4)
    $fire.AddTone(0.005, 0.31, 215.0, 95.0, 0.52, 0.001, 4.0)
    $fire.AddNoise(0.03, 0.88, 0.46, 0.015, 1.7, 420.0, 8200.0)
    $fire.AddTone(0.07, 0.82, 1450.0, 760.0, 0.25, 0.01, 2.3)
    $fire.AddCrackles(0.015, 0.25, 6, 0.34)
    Export-FlareSound 'flare_fire' $fire

    $flight = [FlareSoundBuilder]::new(1.62, 691205)
    $flight.AddNoise(0.00, 1.62, 0.70, 0.08, 0.45, 720.0, 11500.0)
    $flight.AddTone(0.00, 1.56, 1260.0, 860.0, 0.23, 0.05, 0.45)
    $flight.AddTone(0.04, 1.42, 630.0, 430.0, 0.13, 0.04, 0.55)
    $flight.AddCrackles(0.08, 1.35, 14, 0.16)
    Export-FlareSound 'flare_flight' $flight

    $ignite = [FlareSoundBuilder]::new(0.92, 691206)
    $ignite.AddNoise(0.00, 0.26, 0.94, 0.001, 3.2, 70.0, 12000.0)
    $ignite.AddTone(0.00, 0.42, 155.0, 72.0, 0.74, 0.001, 3.2)
    $ignite.AddNoise(0.035, 0.83, 0.55, 0.015, 1.25, 800.0, 13000.0)
    $ignite.AddTone(0.05, 0.70, 1920.0, 1180.0, 0.20, 0.008, 2.4)
    $ignite.AddCrackles(0.02, 0.52, 11, 0.30)
    Export-FlareSound 'flare_ignite' $ignite

    $burn = [FlareSoundBuilder]::new(1.92, 691207)
    $burn.AddNoise(0.00, 1.92, 0.78, 0.09, 0.28, 950.0, 13500.0)
    $burn.AddNoise(0.00, 1.92, 0.23, 0.12, 0.35, 80.0, 980.0)
    $burn.AddTone(0.00, 1.86, 720.0, 670.0, 0.075, 0.10, 0.35)
    $burn.AddCrackles(0.10, 1.62, 24, 0.22)
    Export-FlareSound 'flare_burn' $burn
}
finally {
    Remove-Item -LiteralPath $temporaryDir -Recurse -Force -ErrorAction SilentlyContinue
}
