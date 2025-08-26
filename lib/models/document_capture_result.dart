import 'dart:ui';

class DocumentCaptureResult {
  DocumentCaptureResult({
    required this.imagePath,
    required this.width,
    required this.height,
    required this.corners,
    required this.cornersNormalized,
  });

  final String imagePath;
  final int width;
  final int height;
  final List<Offset> corners;           // pixel space
  final List<Offset> cornersNormalized; // 0..1

  factory DocumentCaptureResult.fromMap(Map<dynamic, dynamic> map) {
    List<Offset> _toOffsets(dynamic list) => (list as List)
        .map((e) => Offset((e['x'] as num).toDouble(), (e['y'] as num).toDouble()))
        .toList();

    return DocumentCaptureResult(
      imagePath: map['imagePath'] as String,
      width: map['width'] as int,
      height: map['height'] as int,
      corners: _toOffsets(map['corners']),
      cornersNormalized: _toOffsets(map['cornersNormalized']),
    );
  }
}