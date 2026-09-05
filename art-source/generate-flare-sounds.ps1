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

    public void AddSustainedNoise(
        double start,
        double duration,
        double amplitude,
        double attack,
        double release,
        double highPass,
        double lowPass,
        double modulationFrequency,
        double modulationDepth)
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
            double attackGain = attack <= 0.0
                ? 1.0 : Math.Min(1.0, i / (attack * SampleRate));
            double releaseGain = release <= 0.0
                ? 1.0 : Math.Min(1.0, (count - 1 - i) / (release * SampleRate));
            double modulation = 1.0 - modulationDepth * 0.5
                + modulationDepth * 0.5 * Math.Sin(
                    Math.PI * 2.0 * modulationFrequency * i / SampleRate
                );
            samples[first + i] += bandState * amplitude
                * attackGain * releaseGain * modulation;
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
    # Break-action latch, hinge movement and the barrel reaching its stop.
    $open = [FlareSoundBuilder]::new(0.34, 691201)
    $open.AddNoise(0.00, 0.045, 0.95, 0.0003, 5.0, 550.0, 12000.0)
    $open.AddTone(0.002, 0.13, 3300.0, 1750.0, 0.34, 0.0003, 5.2)
    $open.AddTone(0.006, 0.17, 980.0, 610.0, 0.24, 0.0005, 4.0)
    $open.AddSustainedNoise(0.07, 0.16, 0.15, 0.02, 0.03, 90.0, 1600.0, 7.0, 0.25)
    $open.AddNoise(0.225, 0.045, 0.58, 0.0003, 5.5, 700.0, 10500.0)
    $open.AddTone(0.228, 0.09, 2350.0, 1450.0, 0.18, 0.0003, 5.5)
    Export-FlareSound 'flare_reload_open' $open

    # Brass shell sliding into the chamber, rim contact and a padded seat thump.
    $insert = [FlareSoundBuilder]::new(0.40, 691202)
    $insert.AddSustainedNoise(0.015, 0.19, 0.19, 0.025, 0.025, 500.0, 3600.0, 11.0, 0.20)
    $insert.AddNoise(0.165, 0.035, 0.72, 0.0003, 5.0, 900.0, 12500.0)
    $insert.AddTone(0.166, 0.12, 4150.0, 2050.0, 0.27, 0.0003, 5.0)
    $insert.AddTone(0.195, 0.18, 205.0, 105.0, 0.64, 0.0008, 4.8)
    $insert.AddNoise(0.205, 0.09, 0.30, 0.0005, 4.0, 110.0, 2600.0)
    $insert.AddCrackles(0.235, 0.055, 2, 0.18)
    Export-FlareSound 'flare_reload_insert' $insert

    # Heavy barrel closure followed by the compact locking click.
    $close = [FlareSoundBuilder]::new(0.36, 691203)
    $close.AddNoise(0.00, 0.065, 1.00, 0.0003, 5.2, 90.0, 10500.0)
    $close.AddTone(0.002, 0.22, 185.0, 82.0, 0.82, 0.0005, 5.0)
    $close.AddTone(0.004, 0.13, 780.0, 410.0, 0.38, 0.0003, 5.5)
    $close.AddNoise(0.105, 0.038, 0.68, 0.0003, 5.8, 850.0, 13000.0)
    $close.AddTone(0.108, 0.11, 2850.0, 1600.0, 0.23, 0.0003, 6.0)
    Export-FlareSound 'flare_reload_close' $close

    # A flare pistol has a short primer click and a broad low-pressure muzzle
    # report. Two filtered reflections give it outdoor space without a rifle crack.
    $fire = [FlareSoundBuilder]::new(0.58, 691204)
    $fire.AddNoise(0.000, 0.018, 0.34, 0.0002, 7.0, 1600.0, 14000.0)
    $fire.AddTone(0.001, 0.055, 3900.0, 2100.0, 0.13, 0.0002, 7.0)
    $fire.AddNoise(0.012, 0.095, 1.00, 0.0002, 4.8, 32.0, 12500.0)
    $fire.AddTone(0.013, 0.31, 92.0, 44.0, 0.96, 0.0004, 4.2)
    $fire.AddTone(0.017, 0.21, 188.0, 83.0, 0.52, 0.0004, 4.8)
    $fire.AddNoise(0.025, 0.28, 0.47, 0.001, 3.0, 115.0, 4700.0)
    $fire.AddNoise(0.165, 0.075, 0.30, 0.001, 4.5, 180.0, 6800.0)
    $fire.AddTone(0.168, 0.19, 128.0, 66.0, 0.24, 0.001, 4.0)
    $fire.AddNoise(0.325, 0.10, 0.18, 0.002, 3.8, 230.0, 5200.0)
    Export-FlareSound 'flare_fire' $fire

    $flight = [FlareSoundBuilder]::new(1.46, 691205)
    $flight.AddSustainedNoise(0.00, 1.46, 0.68, 0.045, 0.10, 820.0, 12500.0, 7.2, 0.28)
    $flight.AddSustainedNoise(0.00, 1.46, 0.16, 0.05, 0.12, 120.0, 1450.0, 3.6, 0.18)
    $flight.AddTone(0.02, 1.36, 510.0, 390.0, 0.055, 0.04, 0.22)
    $flight.AddCrackles(0.08, 1.24, 10, 0.12)
    Export-FlareSound 'flare_flight' $flight

    $ignite = [FlareSoundBuilder]::new(0.76, 691206)
    $ignite.AddNoise(0.00, 0.075, 0.92, 0.0003, 5.0, 75.0, 13000.0)
    $ignite.AddTone(0.002, 0.20, 142.0, 68.0, 0.58, 0.0005, 4.5)
    $ignite.AddSustainedNoise(0.035, 0.70, 0.53, 0.012, 0.09, 980.0, 14000.0, 8.5, 0.25)
    $ignite.AddSustainedNoise(0.05, 0.65, 0.13, 0.015, 0.10, 130.0, 1800.0, 4.0, 0.20)
    $ignite.AddCrackles(0.025, 0.55, 9, 0.23)
    Export-FlareSound 'flare_ignite' $ignite

    $burn = [FlareSoundBuilder]::new(1.78, 691207)
    $burn.AddSustainedNoise(0.00, 1.78, 0.72, 0.07, 0.10, 1050.0, 14500.0, 9.2, 0.30)
    $burn.AddSustainedNoise(0.00, 1.78, 0.20, 0.08, 0.11, 95.0, 1250.0, 4.6, 0.24)
    $burn.AddTone(0.00, 1.72, 460.0, 420.0, 0.035, 0.08, 0.12)
    $burn.AddCrackles(0.08, 1.56, 20, 0.18)
    Export-FlareSound 'flare_burn' $burn
}
finally {
    Remove-Item -LiteralPath $temporaryDir -Recurse -Force -ErrorAction SilentlyContinue
}
