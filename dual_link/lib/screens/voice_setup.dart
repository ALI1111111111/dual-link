import 'dart:async';

import 'package:flutter/material.dart';

import '../native.dart';

/// Phone A: opt-in "talk from Phone B" (Advanced). Off by default so normal users never need
/// Developer options; when turned on it explains the trade-offs and walks through the one-time setup.
class VoiceSetupCard extends StatefulWidget {
  const VoiceSetupCard({super.key});

  @override
  State<VoiceSetupCard> createState() => _VoiceSetupCardState();
}

class _VoiceSetupCardState extends State<VoiceSetupCard> with WidgetsBindingObserver {
  Map<String, dynamic> _a = {};
  Map<String, dynamic> _s = {};
  StreamSubscription? _sub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    _sub = Native.events.listen((e) { if (e['type'] == 'adb' || e['type'] == 'gateway') _refresh(); });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh(); // back from Settings
  }

  Future<void> _refresh() async {
    final a = await Native.adbState();
    final s = await Native.state();
    if (mounted) setState(() { _a = a; _s = s; });
  }

  Future<void> _enterCode() async {
    final c = TextEditingController();
    final code = await showDialog<String>(
      context: context,
      builder: (d) => AlertDialog(
        title: const Text('Pairing code'),
        content: TextField(
          controller: c,
          autofocus: true,
          keyboardType: TextInputType.number,
          maxLength: 6,
          decoration: const InputDecoration(hintText: '6 digits shown by Android'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(d), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(d, c.text.trim()), child: const Text('Pair')),
        ],
      ),
    );
    if (code != null && code.isNotEmpty) await Native.adbPair(code);
    _refresh();
  }

  Widget _step(int n, String title, String text, {required bool done, Widget? action}) {
    final scheme = Theme.of(context).colorScheme;
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: CircleAvatar(
        radius: 14,
        backgroundColor: done ? scheme.primary : scheme.surfaceContainerHighest,
        child: done ? Icon(Icons.check, size: 16, color: scheme.onPrimary) : Text('$n'),
      ),
      title: Text(title),
      subtitle: Text(text),
      trailing: done ? null : action,
    );
  }

  /// Explains exactly what turning voice on involves before anything changes.
  Future<void> _toggle(bool on) async {
    if (on) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (d) => AlertDialog(
          title: const Text('Talk from Phone B?'),
          content: const SingleChildScrollView(
            child: Text(
              'Lets you hear and speak on calls from Phone B.\n\n'
              'Android only lets its own system tools reach call audio, so Dual Link uses '
              'Android\'s built-in Wireless debugging on this phone:\n\n'
              '• One-time setup (about 1 minute): turn on Developer options and Wireless debugging, '
              'then type a 6-digit pairing code.\n'
              '• Developer options must stay on. Some banking or payment apps refuse to open '
              'while it is on.\n'
              '• Works on this Wi-Fi only. After a restart it turns itself back on.\n'
              '• Everything stays on your two phones: no root, no PC, no internet.\n\n'
              'Without this, Phone B still shows calls and can decline, answer on Phone A, '
              'end calls and dial. You can turn this off any time.'),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(d, false), child: const Text('Not now')),
            FilledButton(onPressed: () => Navigator.pop(d, true), child: const Text('Turn on')),
          ],
        ),
      );
      if (ok != true) return;
    }
    await Native.setVoiceOn(on);
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    if (_a.isEmpty || _a['supported'] != true) return const SizedBox.shrink(); // needs Android 13+
    final scheme = Theme.of(context).colorScheme;
    final voiceOn = _a['voiceOn'] == true;
    if (!voiceOn) {
      return Card(
        child: SwitchListTile(
          secondary: const Icon(Icons.record_voice_over_outlined),
          title: const Text('Advanced: talk from Phone B'),
          subtitle: const Text('Off — answer calls on this phone. Phone B still shows calls, declines, ends and dials.'),
          value: false,
          onChanged: _toggle,
        ),
      );
    }
    final paired = _a['paired'] == true;
    final helper = (_s['voiceHelper'] as String?) ?? 'not_started';
    final ready = paired && (helper == 'ready' || helper == 'on');
    final busy = _a['busy'] == true;
    final err = _a['error'] as String?;

    return Card(
      color: ready ? scheme.primaryContainer : null,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Icon(ready ? Icons.record_voice_over : Icons.voice_over_off),
              const SizedBox(width: 12),
              Expanded(child: Text(ready ? 'Talk from Phone B: ready' : 'Talk from Phone B: finish setup',
                  style: Theme.of(context).textTheme.titleMedium)),
              if (busy) const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
              Switch(value: true, onChanged: _toggle),
            ]),
            if (!ready) ...[
              const SizedBox(height: 4),
              const Text('One-time setup on this phone (about 1 minute).'),
              _step(1, 'Turn on Developer options',
                  'Settings → About phone → tap "Build number" 7 times.',
                  done: _a['devOptions'] == true,
                  action: TextButton(onPressed: Native.adbOpenDevSettings, child: const Text('Open'))),
              _step(2, 'Turn on Wireless debugging',
                  'Developer options → Wireless debugging → On (needs Wi-Fi).',
                  done: _a['wirelessDebugging'] == true || paired,
                  action: TextButton(onPressed: Native.adbOpenDevSettings, child: const Text('Open'))),
              _step(3, 'Pair Dual Link',
                  'Tap "Pair device with pairing code", keep the code on screen, '
                      'then type it into Dual Link\'s notification.',
                  done: paired,
                  action: TextButton(
                    onPressed: () async { await Native.adbAskCode(); await Native.adbOpenDevSettings(); },
                    child: const Text('Start'))),
              if (!paired)
                Align(alignment: Alignment.centerRight,
                    child: TextButton(onPressed: _enterCode, child: const Text('Type code here instead'))),
              if (paired)
                Align(alignment: Alignment.centerRight,
                    child: FilledButton.tonal(onPressed: busy ? null : Native.adbStartVoice, child: const Text('Start voice now'))),
              if (err != null)
                Text(err, style: TextStyle(color: scheme.error)),
            ] else
              Text(_a['autoRecover'] == true
                  ? 'Starts again by itself after a restart (Wireless debugging is turned back on automatically on Wi-Fi).'
                  : 'If voice stops after a restart, open Dual Link and tap Start voice.'),
          ],
        ),
      ),
    );
  }
}
