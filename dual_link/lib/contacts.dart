import 'native.dart';

/// Phone B's address book, loaded once. Numbers are already normalized by the native side.
class ContactBook {
  static List<Map<String, String>> all = [];
  static final Map<String, String> _byNumber = {};
  static Future<void>? _loading;

  static Future<void> load({bool ask = true}) => _loading ??= () async {
        if (ask) await Native.requestPermissions(['contacts']);
        all = await Native.contacts();
        _byNumber
          ..clear()
          ..addEntries(all.map((c) => MapEntry(c['number']!, c['name']!)));
      }();

  /// Contact name for a conversation address, or null if unknown.
  static String? name(String address) => _byNumber[address];
}
