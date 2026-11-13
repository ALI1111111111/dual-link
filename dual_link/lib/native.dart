import 'package:flutter/services.dart';

/// Talks to the Android services (GatewayService / ClientService).
/// False in the Play Store build (--dart-define=VOICE=false): no "talk from Phone B".
const bool kVoiceFeature = bool.fromEnvironment('VOICE', defaultValue: true);

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
  static Future<void> openPrivacy() => _m.invokeMethod('openPrivacy');

  /// Talk on Phone A's call from this phone.
  static Future<bool> voiceStart() async => (await _m.invokeMethod('voiceStart')) as bool;
  static Future<void> voiceStop() => _m.invokeMethod('voiceStop');
  static Future<void> voiceSpeaker(bool on) => _m.invokeMethod('voiceSpeaker', {'on': on});
  static Future<void> voiceMute(bool on) => _m.invokeMethod('voiceMute', {'on': on});

  static Future<bool> canFullScreen() async => (await _m.invokeMethod('canFullScreen')) as bool;
  static Future<void> openFullScreenSettings() => _m.invokeMethod('openFullScreenSettings');

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

  /// Called while the app is open. {action: sms|call|calls, address, body}
  static void onOpen(void Function(Map<String, String?> req) cb) {
    _m.setMethodCallHandler((call) async {
      if (call.method == 'openThread') cb({'action': 'sms', 'address': call.arguments as String});
      if (call.method == 'openCompose') cb(Map<String, String?>.from(call.arguments as Map));
    });
  }

  /// Phone A's call history: [{id, number, type (1 in, 2 out, 3 missed, 5 rejected), ts, dur, sim}].
  static Future<List<Map<String, dynamic>>> calls() async =>
      ((await _m.invokeMethod('calls')) as List).map((e) => Map<String, dynamic>.from(e as Map)).toList();

  /// decline / answer / end / dial on Phone A. False if Phone A isn't connected.
  static Future<bool> callCmd(String cmd, {String? number, int? sim}) async =>
      (await _m.invokeMethod('callCmd', {'cmd': cmd, 'number': number, 'sim': sim})) as bool;

  // ---- Phone A: voice setup through its own Wireless debugging (no PC) ----
  static Future<Map<String, dynamic>> adbState() => _map('adbState');
  static Future<void> adbOpenDevSettings() => _m.invokeMethod('adbOpenDevSettings');
  static Future<void> adbAskCode() => _m.invokeMethod('adbAskCode');
  static Future<void> adbPair(String code) => _m.invokeMethod('adbPair', {'code': code});
  static Future<void> adbStartVoice() => _m.invokeMethod('adbStartVoice');
  static Future<void> setVoiceOn(bool on) => _m.invokeMethod('setVoiceOn', {'on': on});
}
