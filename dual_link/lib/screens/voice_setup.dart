import 'dart:async';

import 'package:flutter/material.dart';

import '../native.dart';

/// Phone A: one-time setup so Phone B can hear and talk on calls (no PC, no root).
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

  @override
  Widget build(BuildContext context) {
    if (_a.isEmpty) return const SizedBox.shrink();
    final scheme = Theme.of(context).colorScheme;
    if (_a['supported'] != true) {
      return const Card(child: ListTile(
        leading: Icon(Icons.phone_disabled),
        title: Text('Voice on Phone B needs Android 13+ on this phone'),
        subtitle: Text('Call alerts, decline and dial still work.'),
      ));
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
              Expanded(child: Text(ready ? 'Voice calls on Phone B: ready' : 'Set up voice calls on Phone B',
                  style: Theme.of(context).textTheme.titleMedium)),
              if (busy) const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
            ]),
            if (!ready) ...[
              const SizedBox(height: 4),
              const Text('One-time setup on this phone. Uses Android\'s own Wireless debugging; '
                  'nothing leaves your phones, no root, no PC.'),
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
