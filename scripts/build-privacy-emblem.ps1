param(
    [Parameter(Mandatory = $true)]
    [string] $SourcePath,
    [Parameter(Mandatory = $true)]
    [string] $OutputPath
)

Add-Type -AssemblyName System.Drawing

$source = [System.Drawing.Bitmap]::FromFile($SourcePath)
$mask = [System.Drawing.Bitmap]::new(
    $source.Width,
    $source.Height,
    [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
)

try {
    $copyGraphics = [System.Drawing.Graphics]::FromImage($mask)
    try {
        $copyGraphics.DrawImageUnscaled($source, 0, 0)
    } finally {
        $copyGraphics.Dispose()
    }

    $rect = [System.Drawing.Rectangle]::new(0, 0, $mask.Width, $mask.Height)
    $data = $mask.LockBits(
        $rect,
        [System.Drawing.Imaging.ImageLockMode]::ReadWrite,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    try {
        $bytes = [byte[]]::new([Math]::Abs($data.Stride) * $data.Height)
        [Runtime.InteropServices.Marshal]::Copy($data.Scan0, $bytes, 0, $bytes.Length)
        for ($i = 0; $i -lt $bytes.Length; $i += 4) {
            $luma = [int](($bytes[$i + 2] * 299 + $bytes[$i + 1] * 587 + $bytes[$i] * 114) / 1000)
            $alpha = if ($luma -le 185) {
                255
            } elseif ($luma -ge 232) {
                0
            } else {
                [int]((232 - $luma) * 255 / 47)
            }
            $bytes[$i] = 127
            $bytes[$i + 1] = 119
            $bytes[$i + 2] = 119
            $bytes[$i + 3] = [byte]$alpha
        }
        [Runtime.InteropServices.Marshal]::Copy($bytes, 0, $data.Scan0, $bytes.Length)
    } finally {
        $mask.UnlockBits($data)
    }

    $graphics = [System.Drawing.Graphics]::FromImage($mask)
    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy

        # Remove the generated, slightly warped mid-level fragments.
        $eraser = [System.Drawing.Pen]::new([System.Drawing.Color]::Transparent, 44)
        try {
            $eraser.StartCap = [System.Drawing.Drawing2D.LineCap]::Flat
            $eraser.EndCap = [System.Drawing.Drawing2D.LineCap]::Flat
            $graphics.DrawLine($eraser, 65, 684, 480, 559)
            $graphics.DrawLine($eraser, 774, 559, 1189, 684)
        } finally {
            $eraser.Dispose()
        }

        $fog = [System.Drawing.Color]::FromArgb(255, 119, 119, 127)
        # All rear-framework strokes share one fixed width.
        $pen = [System.Drawing.Pen]::new($fog, 13)
        try {
            $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Flat
            $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Flat
            $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Miter

            # Restore the two uninterrupted outer diagonals after clearing the old middle band.
            $graphics.DrawLine($pen, 160, 443, 463, 789)
            $graphics.DrawLine($pen, 1094, 443, 791, 789)

            # Two straight segments sharing a sharp top corner; exact mirror pair.
            $graphics.DrawLines($pen, [System.Drawing.PointF[]]@(
                [System.Drawing.PointF]::new(568, 336.68),
                [System.Drawing.PointF]::new(578, 354),
                [System.Drawing.PointF]::new(550.36, 510.74)
            ))
            $graphics.DrawLines($pen, [System.Drawing.PointF[]]@(
                [System.Drawing.PointF]::new(686, 336.68),
                [System.Drawing.PointF]::new(676, 354),
                [System.Drawing.PointF]::new(703.64, 510.74)
            ))

            # Straight and collinear rear-ray fragments; gaps are foreground occlusion.
            $graphics.DrawLine($pen, 65, 684, 285, 612.6)
            $graphics.DrawLine($pen, 321, 600.9, 360, 588.2)
            $graphics.DrawLine($pen, 406, 573.3, 443, 561.3)
            $graphics.DrawLine($pen, 969, 612.6, 1189, 684)
            $graphics.DrawLine($pen, 894, 588.2, 933, 600.9)
            $graphics.DrawLine($pen, 811, 561.3, 848, 573.3)
        } finally {
            $pen.Dispose()
        }
    } finally {
        $graphics.Dispose()
    }

    $output = [System.Drawing.Bitmap]::new(
        512,
        512,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    try {
        $resizeGraphics = [System.Drawing.Graphics]::FromImage($output)
        try {
            $resizeGraphics.Clear([System.Drawing.Color]::Transparent)
            $resizeGraphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
            $resizeGraphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
            $resizeGraphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $resizeGraphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $resizeGraphics.DrawImage($mask, [System.Drawing.Rectangle]::new(0, 0, 512, 512))
        } finally {
            $resizeGraphics.Dispose()
        }
        $output.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $output.Dispose()
    }
} finally {
    $mask.Dispose()
    $source.Dispose()
}
