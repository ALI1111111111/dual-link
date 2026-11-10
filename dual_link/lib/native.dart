import 'package:flutter/services.dart';

/// Talks to the Android services (GatewayService / ClientService).
class Native {
  static const _m = MethodChannel('duallink/native');
  static const _e = EventChannel('duallink/events');

  /// Events: {type: gateway|client|sms|status, ...}
  static final Stream<Map<String, dynamic>> events = _e
      .receiveBroadcastStream()
      .map((e) => Map<String, dynamic>.from(e as Map))
      .asBroadcastStream();

  static Future<Map<String, dynamic>> _map(String method, [Map<String, dynamic>? args]) async =>
      Map<String, dynamic>.from(await _m.invokeMethod(method, args) as Map);

  static Future<Map<String, dynamic>> state() => _map('getState');
  static Future<Map<String, dynamic>> startGateway() => _map('startGateway');
  static Future<Map<String, dynamic>> pairClient(String qr) => _map('pairClient', {'qr': qr});
  static Future<Map<String, dynamic>> rotateKey() => _map('rotateKey');
  static Future<Map<String, dynamic>> stop() => _map('stop');
  static Future<Map<String, dynamic>> resume() => _map('resume');
  static Future<Map<String, dynamic>> reset() => _map('reset');

  /// [sim] = Phone A's SIM subscription id, -1 = Phone A's default SMS SIM.
  static Future<void> sendSms(String to, String body, {int sim = -1}) =>
      _m.invokeMethod('sendSms', {'to': to, 'body': body, 'sim': sim});

  static Future<List<Map<String, String>>> contacts() async =>
      ((await _m.invokeMethod('contacts')) as List).map((e) => Map<String, String>.from(e as Map)).toList();

  static Future<List<Map<String, dynamic>>> threads() async =>
      ((await _m.invokeMethod('threads')) as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();

  static Future<List<Map<String, dynamic>>> messages(String address) async =>
      ((await _m.invokeMethod('messages', {'address': address}) as List))
          .map((e) => Map<String, dynamic>.from(e as Map))
          .toList();

  static Future<void> markRead(String address) => _m.invokeMethod('markRead', {'address': address});

  /// names: 'sms', 'notifications', 'camera'. True if all granted.
  static Future<bool> requestPermissions(List<String> names) async =>
      (await _m.invokeMethod('requestPermissions', {'names': names})) as bool;
  static Future<void> openAppSettings() => _m.invokeMethod('openAppSettings');

  static Future<bool> isIgnoringBattery() async => (await _m.invokeMethod('isIgnoringBattery')) as bool;
  static Future<void> requestIgnoreBattery() => _m.invokeMethod('requestIgnoreBattery');

  /// Called when an SMS notification is tapped while the app is open.
  static void onOpenThread(void Function(String address) cb) {
    _m.setMethodCallHandler((call) async {
      if (call.method == 'openThread') cb(call.arguments as String);
    });
  }
}
