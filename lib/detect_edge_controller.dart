import 'package:flutter/services.dart';

class DetectEdgeController {
  DetectEdgeController();

  MethodChannel? _channel;
  int? _id;

  /// Bind the controller to the native view's ID
  void bindToView(int id) {
    _id = id;
    _channel = MethodChannel('edge_detection/camera_view_$_id');
  }

  Future<void> start() async {
    if (_channel != null) {
      await _channel!.invokeMethod('start');
    }
  }

  Future<void> stop() async {
    if (_channel != null) {
      await _channel!.invokeMethod('stop');
    }
  }

  Future<void> toggleFlash() async {
    if (_channel != null) {
      await _channel!.invokeMethod('toggleFlash');
    }
  }

  /// Capture an image and return its path
  Future<bool> capture() async {
    if (_channel != null) {
      final res = await _channel!.invokeMethod('capture');
      return res;
    }
    return false;
  }

  /// Listen for native method calls (events)
  void setMethodCallHandler(Future<dynamic> Function(MethodCall call)? handler) {
    _channel?.setMethodCallHandler(handler);
  }
}
