import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

class CropView extends StatefulWidget {
  final String imagePath;
  final String savePath;
  final String cropTitle;

  const CropView({
    Key? key,
    required this.imagePath,
    required this.savePath,
    this.cropTitle = "Crop Document",
  }) : super(key: key);

  @override
  State<CropView> createState() => _CropViewState();
}

class _CropViewState extends State<CropView> {
  late MethodChannel _channel;

  @override
  Widget build(BuildContext context) {
    return AndroidView(
      viewType: "edge_detection/crop_view",
      creationParams: {
        "imagePath": widget.imagePath,
        "savePath": widget.savePath,
        "cropTitle": widget.cropTitle,
      },
      creationParamsCodec: const StandardMessageCodec(),
      onPlatformViewCreated: (id) {
        _channel = MethodChannel("edge_detection/crop_view_$id");
        _channel.setMethodCallHandler((call) async {
          if (call.method == "onCropped") {
            print("Cropping done!");
          }
        });
      },
    );
  }

  Future<void> rotate() => _channel.invokeMethod("rotate");
  Future<void> enhance() => _channel.invokeMethod("enhance");
  Future<void> reset() => _channel.invokeMethod("reset");
  Future<String?> save() => _channel.invokeMethod("save");
}
