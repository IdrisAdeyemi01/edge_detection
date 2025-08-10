import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

typedef OnImageCaptured = void Function(String path);



class DetectEdgeCameraViewer extends StatefulWidget {
  const DetectEdgeCameraViewer({
    Key? key,
    this.canUseGallery = true,
    this.onImageCaptured,
  }) : super(key: key);

  final bool canUseGallery;
  final OnImageCaptured? onImageCaptured;

  @override
  State<DetectEdgeCameraViewer> createState() => _DetectEdgeCameraViewerState();
}

class _DetectEdgeCameraViewerState extends State<DetectEdgeCameraViewer> {
  DetectEdgeController? controller;

  @override
  Widget build(BuildContext context) {
    // creationParams will be available to the Kotlin PlatformViewFactory
    final creationParams = <String, dynamic>{
      'can_use_gallery': widget.canUseGallery,
      'scan_title': 'Scanning',
      'crop_title': 'Crop',
      // ... other params
    };

    return AndroidView(
      viewType: 'edge_detection/camera_view',
      creationParams: creationParams,
      creationParamsCodec: const StandardMessageCodec(),
      onPlatformViewCreated: (id) {
        controller = DetectEdgeController._(id);
        final channel = MethodChannel('edge_detection/camera_view_$id');
        channel.setMethodCallHandler((call) async {
          if (call.method == 'onImageCaptured') {
            final String path = call.arguments as String;
            widget.onImageCaptured?.call(path);
          }
        });
      },
    );
  }

  @override
  void dispose() {
    controller?.stop();
    super.dispose();
  }
}



class DetectEdgeController {
  DetectEdgeController._(this._id) {
    _channel = MethodChannel('edge_detection/camera_view_$_id');
  }

  final int _id;
  late MethodChannel _channel;

  Future<void> start() => _channel.invokeMethod('start');
  Future<void> stop() => _channel.invokeMethod('stop');
  Future<void> toggleFlash() => _channel.invokeMethod('toggleFlash');

  /// returns true/false or you can change native to return path
  Future<String?> capture() async {
    final res = await _channel.invokeMethod('capture');
    // if native is sending path via onImageCaptured, use an event handler instead.
    return res as String?;
  }
}