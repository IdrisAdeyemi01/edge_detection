import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'detect_edge_controller.dart';
import 'models/document_capture_result.dart';

typedef OnCaptureResult = void Function(DocumentCaptureResult result);

class DetectEdgeCameraViewer extends StatefulWidget {
  const DetectEdgeCameraViewer({
    required this.controller,
    Key? key,
    this.canUseGallery = true,
    this.enableAutoCapture = true,
    this.onCaptureResult,
    this.saveTo,
  }) : super(key: key);

  final bool canUseGallery;
  final bool enableAutoCapture;
  final OnCaptureResult? onCaptureResult;
  final DetectEdgeController controller;
  final String? saveTo; // optional native save path

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
      if (widget.saveTo != null) 'save_to': widget.saveTo,
    };

    return AndroidView(
      viewType: "edge_detection/camera_view",
      creationParams: creationParams,
      creationParamsCodec: const StandardMessageCodec(),
      onPlatformViewCreated: (id) {
        widget.controller.bindToView(id);
        widget.controller.start();

        widget.controller.setAutoCaptureEnabled(widget.enableAutoCapture);
        widget.controller.setAutoCaptureStability();

        widget.controller.setMethodCallHandler((call) async {
          if (call.method == 'onCaptureResult') {
            final res = DocumentCaptureResult.fromMap(
              (call.arguments as Map).cast<dynamic, dynamic>(),
            );

            widget.onCaptureResult?.call(res);
            widget.controller.completePendingCapture(res);
          }
        });
        // Configure auto-capture after channel is ready
        setState(() {});
      },
    );
  }

  @override
  void dispose() {
    widget.controller.stop();
    super.dispose();
  }
}
