import 'dart:async';
import 'package:flutter/services.dart';
import 'models/document_capture_result.dart';

class DetectEdgeController {
  DetectEdgeController();

  MethodChannel? _channel;
  int? _id;
  Completer<DocumentCaptureResult>? _pending;

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

  Future<void> setAutoCaptureEnabled(bool enableAutoCapture) async {
    if (_channel != null) {
      await _channel!.invokeMethod(
          'setAutoCaptureEnabled', {'enabled': enableAutoCapture});
    }
  }

  Future<void> setAutoCaptureStability() async {
    if (_channel != null) {
      _channel!.invokeMethod('setAutoCaptureStability', {
        'minStableFrames': 5,
        'maxCornerMove': 20.0,
        'minQuadArea': 0.10,
      });
    }
  }

  Future<DocumentCaptureResult> capture() async {
    _pending = Completer<DocumentCaptureResult>();
    await _channel!.invokeMethod('capture');
    return _pending!.future;
  }

  /// Listen for native method calls (events)
  void setMethodCallHandler(
      Future<dynamic> Function(MethodCall call)? handler) {
    _channel?.setMethodCallHandler(handler);
  }

  void completePendingCapture(DocumentCaptureResult r) {
    _pending?.complete(r);
    _pending = null;
  }
}
