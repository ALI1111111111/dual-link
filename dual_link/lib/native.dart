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

  static Future<void> deleteThread(String address) => _m.invokeMethod('deleteThread', {'address': address});

  /// Default SMS app (Phone B).
  static Future<bool> isDefaultSms() async => (await _m.invokeMethod('isDefaultSms')) as bool;
  static Future<void> requestDefaultSms() => _m.invokeMethod('requestDefaultSms');

  /// {address, body} if the app was launched from a notification, Contacts or an sms: link.
  static Future<Map<String, String?>?> takeLaunchCompose() async {
    final r = await _m.invokeMethod('takeLaunchCompose');
    return r == null ? null : Map<String, String?>.from(r as Map);
  }

  /// Called while the app is open: notification tapped, or "Message" from Contacts / an sms: link.
  static void onOpen(void Function(String address, String? body) cb) {
    _m.setMethodCallHandler((call) async {
      if (call.method == 'openThread') cb(call.arguments as String, null);
      if (call.method == 'openCompose') {
        final a = Map<String, dynamic>.from(call.arguments as Map);
        cb(a['address'] as String, a['body'] as String?);
      }
    });
  }
}
