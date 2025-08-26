// flutter_crop_view.dart
import 'dart:io';
import 'dart:math' as math;
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:image/image.dart' as img;
import 'package:path/path.dart' as p;
import 'package:vector_math/vector_math_64.dart' as vm;

/// A minimal crop screen:
/// - Shows image
/// - Draggable 4 corners (normalized coordinates)
/// - Perspective warp on "Crop" button -> writes a new JPEG and pops with the path
class FlutterCropView extends StatefulWidget {
  const FlutterCropView({
    Key? key,
    required this.imagePath,
    required this.imageSize,           // original pixel size from native
    required this.initialPolygon,      // normalized 0..1 (TL, TR, BR, BL)
  }) : super(key: key);

  final String imagePath;
  final Size imageSize;
  final List<Offset> initialPolygon; // normalized (length 4)

  @override
  State<FlutterCropView> createState() => _FlutterCropViewState();
}

class _FlutterCropViewState extends State<FlutterCropView> {
  late List<Offset> _poly; // normalized
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    if (widget.initialPolygon.length == 4) {
      _poly = List<Offset>.from(widget.initialPolygon);
    } else {
      // fallback to a centered rectangle
      _poly = const [
        Offset(0.1, 0.1),
        Offset(0.9, 0.1),
        Offset(0.9, 0.9),
        Offset(0.1, 0.9),
      ];
    }
  }

  Future<void> _onCrop() async {
    setState(() => _busy = true);
    try {
      final bytes = await File(widget.imagePath).readAsBytes();
      final src = img.decodeImage(bytes)!;

      // Convert normalized -> pixel points (TL, TR, BR, BL)
      final px = [
        Offset(_poly[0].dx * src.width,  _poly[0].dy * src.height),
        Offset(_poly[1].dx * src.width,  _poly[1].dy * src.height),
        Offset(_poly[2].dx * src.width,  _poly[2].dy * src.height),
        Offset(_poly[3].dx * src.width,  _poly[3].dy * src.height),
      ];

      // Compute output size (average top/bottom widths, left/right heights)
      double d(Offset a, Offset b) =>
          math.sqrt(math.pow(a.dx - b.dx, 2) + math.pow(a.dy - b.dy, 2));
      final width  = ((d(px[0], px[1]) + d(px[3], px[2])) / 2).round().clamp(50, 6000);
      final height = ((d(px[0], px[3]) + d(px[1], px[2])) / 2).round().clamp(50, 6000);

      final warped = _warpPerspective(src, px, width, height);

      final outPath = p.join(
        File(widget.imagePath).parent.path,
        'crop_${DateTime.now().millisecondsSinceEpoch}.jpg',
      );
      await File(outPath).writeAsBytes(img.encodeJpg(warped, quality: 95));

      if (mounted) Navigator.of(context).pop(outPath);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Crop failed: $e')),
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  // --- Perspective warp (nearest neighbor) ----------------------------------
  // 'quad' order: [TL, TR, BR, BL] in SOURCE pixel space.
  img.Image _warpPerspective(img.Image src, List<Offset> quad, int outW, int outH) {
    final dst = img.Image(width: outW, height: outH);
    // Destination rectangle corners
    final dstQuad = [
      vm.Vector3(0,     0,     1),
      vm.Vector3(outW-1.0, 0,  1),
      vm.Vector3(outW-1.0, outH-1.0, 1),
      vm.Vector3(0,     outH-1.0,    1),
    ];
    final srcQuad = [
      vm.Vector3(quad[0].dx, quad[0].dy, 1),
      vm.Vector3(quad[1].dx, quad[1].dy, 1),
      vm.Vector3(quad[2].dx, quad[2].dy, 1),
      vm.Vector3(quad[3].dx, quad[3].dy, 1),
    ];

    // Compute homography H that maps dst -> src (so we can inverse map each dst pixel)
    final H = _computeHomography(dstQuad, srcQuad); // 3x3
    final invW = src.width  - 1;
    final invH = src.height - 1;

    for (int y = 0; y < outH; y++) {
      for (int x = 0; x < outW; x++) {
        final v = H * vm.Vector3(x.toDouble(), y.toDouble(), 1);
        final sx = (v.x / v.z).clamp(0.0, invW.toDouble());
        final sy = (v.y / v.z).clamp(0.0, invH.toDouble());
        // nearest-neighbor
        final px = src.getPixel(sx.round(), sy.round());
        dst.setPixel(x, y, px);
      }
    }
    return dst;
  }

  // Solve homography from four point pairs using a simple 8x8 system
  vm.Matrix3 _computeHomography(List<vm.Vector3> dst, List<vm.Vector3> src) {
    // Build A*h=b where h = [h00 h01 h02 h10 h11 h12 h20 h21]^T and h22=1
    final A = List.generate(8, (_) => List<double>.filled(8, 0));
    final b = List<double>.filled(8, 0);

    for (int i = 0; i < 4; i++) {
      final X = dst[i].x, Y = dst[i].y;
      final x = src[i].x, y = src[i].y;

      final r1 = i * 2;
      final r2 = r1 + 1;

      A[r1][0] = X;   A[r1][1] = Y;   A[r1][2] = 1;  A[r1][3] = 0;   A[r1][4] = 0;   A[r1][5] = 0;   A[r1][6] = -X * x; A[r1][7] = -Y * x;
      b[r1]    = x;

      A[r2][0] = 0;   A[r2][1] = 0;   A[r2][2] = 0;  A[r2][3] = X;   A[r2][4] = Y;   A[r2][5] = 1;   A[r2][6] = -X * y; A[r2][7] = -Y * y;
      b[r2]    = y;
    }

    // Solve with Gaussian elimination
    final h = _solve8(A, b);
    return vm.Matrix3.columns(
      vm.Vector3(h[0], h[3], h[6]),
      vm.Vector3(h[1], h[4], h[7]),
      vm.Vector3(h[2], h[5], 1.0),
    );
  }

  List<double> _solve8(List<List<double>> A, List<double> b) {
    final n = 8;
    // Forward elimination
    for (int i = 0; i < n; i++) {
      // Pivot
      int maxR = i;
      for (int r = i + 1; r < n; r++) {
        if (A[r][i].abs() > A[maxR][i].abs()) maxR = r;
      }
      if (maxR != i) {
        final tmp = A[i]; A[i] = A[maxR]; A[maxR] = tmp;
        final tb = b[i];  b[i]  = b[maxR]; b[maxR] = tb;
      }
      // Normalize pivot row
      final piv = A[i][i];
      if (piv.abs() < 1e-12) continue;
      for (int c = i; c < n; c++) A[i][c] /= piv;
      b[i] /= piv;

      // Eliminate below
      for (int r = i + 1; r < n; r++) {
        final f = A[r][i];
        if (f == 0) continue;
        for (int c = i; c < n; c++) A[r][c] -= f * A[i][c];
        b[r] -= f * b[i];
      }
    }
    // Back substitution
    final x = List<double>.filled(n, 0);
    for (int i = n - 1; i >= 0; i--) {
      double sum = b[i];
      for (int c = i + 1; c < n; c++) sum -= A[i][c] * x[c];
      x[i] = sum / (A[i][i].abs() < 1e-12 ? 1.0 : A[i][i]);
    }
    return x;
  }
  // ----------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Adjust & Crop'),
        actions: [
          TextButton(
            onPressed: _busy ? null : _onCrop,
            child: _busy
                ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('Crop', style: TextStyle(color: Colors.white)),
          ),
        ],
      ),
      body: FutureBuilder<Uint8List>(
        future: File(widget.imagePath).readAsBytes(),
        builder: (ctx, snap) {
          if (!snap.hasData) return const Center(child: CircularProgressIndicator());
          final bytes = snap.data!;
          // Keep overlay math easy: use AspectRatio == original image
          return LayoutBuilder(
            builder: (context, constraints) {
              final iw = widget.imageSize.width;
              final ih = widget.imageSize.height;
              final aspect = iw / ih;

              // We render in a box that has the same aspect as the image,
              // so normalized -> screen mapping is linear and trivial.
              final maxW = constraints.maxWidth;
              final maxH = constraints.maxHeight;
              double w = maxW, h = w / aspect;
              if (h > maxH) { h = maxH; w = h * aspect; }

              return Center(
                child: SizedBox(
                  width: w,
                  height: h,
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      Image.memory(bytes, fit: BoxFit.fill),
                      CustomPaint(
                        painter: _PolygonPainter(_poly),
                      ),
                      // 4 draggable handles
                      for (int i = 0; i < 4; i++)
                        _Handle(
                          normalized: _poly[i],
                          onDrag: (posNorm) {
                            setState(() => _poly[i] = posNorm.clamp01());
                          },
                        ),
                    ],
                  ),
                ),
              );
            },
          );
        },
      ),
    );
  }
}

class _PolygonPainter extends CustomPainter {
  _PolygonPainter(this.norm) : super();
  final List<Offset> norm; // 0..1

  @override
  void paint(Canvas canvas, Size size) {
    if (norm.length != 4) return;
    final pts = norm.map((e) => Offset(e.dx * size.width, e.dy * size.height)).toList();

    final edgePaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..color = Colors.cyan;

    final fillPaint = Paint()
      ..style = PaintingStyle.fill
      ..color = Colors.cyan.withOpacity(0.12);

    final path = Path()..addPolygon(pts, true);
    canvas.drawPath(path, fillPaint);
    canvas.drawPath(path, edgePaint);
  }

  @override
  bool shouldRepaint(covariant _PolygonPainter old) => old.norm != norm;
}

class _Handle extends StatelessWidget {
  const _Handle({
    Key? key,
    required this.normalized,
    required this.onDrag,
  }) : super(key: key);

  final Offset normalized; // 0..1 in parent Stack
  final ValueChanged<Offset> onDrag;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, c) {
      final w = c.maxWidth, h = c.maxHeight;
      final px = Offset(normalized.dx * w, normalized.dy * h);
      return Positioned(
        left: px.dx - 14,
        top: px.dy - 14,
        child: GestureDetector(
          onPanUpdate: (d) {
            final nx = (px.dx + d.delta.dx) / w;
            final ny = (px.dy + d.delta.dy) / h;
            onDrag(Offset(nx, ny));
          },
          child: Container(
            width: 28,
            height: 28,
            decoration: BoxDecoration(
              color: Colors.white,
              shape: BoxShape.circle,
              border: Border.all(color: Colors.cyan, width: 2),
              boxShadow: const [BoxShadow(blurRadius: 3, color: Colors.black26)],
            ),
          ),
        ),
      );
    });
  }
}

extension on Offset {
  Offset clamp01() => Offset(
    dx.clamp(0.0, 1.0),
    dy.clamp(0.0, 1.0),
  );
}
