import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

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