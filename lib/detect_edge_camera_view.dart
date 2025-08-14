import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'detect_edge_controller.dart';

typedef OnImageCaptured = void Function(String path);

class DetectEdgeCameraViewer extends StatefulWidget {
  const DetectEdgeCameraViewer({
    required this.controller,
    Key? key,
    this.canUseGallery = true,
    this.onImageCaptured,
  }) : super(key: key);

  final bool canUseGallery;
  final OnImageCaptured? onImageCaptured;
  final DetectEdgeController controller;

  @override
  State<DetectEdgeCameraViewer> createState() => _DetectEdgeCameraViewerState();
}

class _DetectEdgeCameraViewerState extends State<DetectEdgeCameraViewer> {
  @override
  Widget build(BuildContext context) {
    final creationParams = <String, dynamic>{
      'can_use_gallery': widget.canUseGallery,
      'scan_title': 'Scanning',
      'crop_title': 'Crop',
    };

    return AndroidView(
      viewType: "edge_detection/camera_view",
      creationParams: creationParams,
      creationParamsCodec: const StandardMessageCodec(),
      onPlatformViewCreated: (id) {
        widget.controller.bindToView(id);
        widget.controller.start();
        widget.controller.setMethodCallHandler((call) async {
          if (call.method == 'onImageCaptured') {
            final String path = call.arguments as String;
            widget.onImageCaptured?.call(path);
          }
        });
        setState((){});
      },
    );
  }

  @override
  void dispose() {
    widget.controller.stop();
    super.dispose();
  }
}
