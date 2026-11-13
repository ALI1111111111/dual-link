import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../disclosure.dart';
import '../native.dart';
import 'voice_setup.dart';

/// Asks for SMS + notification permission, then turns this phone into the gateway.
class GatewaySetupScreen extends StatefulWidget {
  const GatewaySetupScreen({super.key});

  @override
  State<GatewaySetupScreen> createState() => _GatewaySetupScreenState();
}

class _GatewaySetupScreenState extends State<GatewaySetupScreen> {
  String? _error;

  Future<void> _start() async {
    if (!await showDataDisclosure(context, phoneA: true)) return;
    await Native.requestPermissions(['notifications']);
    final sms = await Native.requestPermissions(['sms']);
    await Native.requestPermissions(['calls']); // optional: call alerts on Phone B
    if (!sms) {
      setState(() => _error = 'SMS permission is needed to forward messages. '
          'If Android blocks it, open App info → ⋮ → "Allow restricted settings", allow SMS, then try again.');
      await Native.openAppSettings();
      return;
    }
    await Native.startGateway();
    if (!await Native.isIgnoringBattery()) await Native.requestIgnoreBattery();
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Set up Phone A')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          const Text('This phone will:'),
          const SizedBox(height: 12),
          const ListTile(leading: Icon(Icons.sms), title: Text('Read incoming SMS and pass them to Phone B')),
          const ListTile(leading: Icon(Icons.send), title: Text('Send SMS from your SIM when Phone B replies')),
          const ListTile(leading: Icon(Icons.call), title: Text('Tell Phone B about calls; let it decline, answer or dial')),
          const ListTile(leading: Icon(Icons.battery_charging_full), title: Text('Run in the background (allow "no battery limits")')),
          const SizedBox(height: 24),
          if (_error != null) ...[
            Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
            const SizedBox(height: 16),
          ],
          FilledButton.icon(onPressed: _start, icon: const Icon(Icons.play_arrow), label: const Text('Allow & start')),
        ],
      ),
    );
  }
}

class GatewayScreen extends StatefulWidget {
  const GatewayScreen({super.key, required this.onReset});
  final VoidCallback onReset;

  @override
  State<GatewayScreen> createState() => _GatewayScreenState();
}

class _GatewayScreenState extends State<GatewayScreen> with WidgetsBindingObserver {
  Map<String, dynamic> _s = {};
  bool _batteryOk = true;
  StreamSubscription? _sub;
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    // Asks only for what's missing (e.g. SMS history + SIM list after an update).
    Native.requestPermissions(['sms'])
        .then((_) => Native.requestPermissions(['calls']))
        .then((_) => _refresh());
    _sub = Native.events.listen((e) {
      if (e['type'] == 'gateway') _refresh();
    });
    // IP can change when Wi-Fi changes; refresh now and then.
    _poll = Timer.periodic(const Duration(seconds: 10), (_) => _refresh());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub?.cancel();
    _poll?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    final s = await Native.state();
    final b = await Native.isIgnoringBattery();
    if (mounted) setState(() { _s = s; _batteryOk = b; });
  }

  @override
  Widget build(BuildContext context) {
    final running = _s['gatewayRunning'] == true;
    final enabled = _s['enabled'] == true;
    final peer = _s['clientConnectedToGateway'] == true;
    final qr = _s['qr'] as String?;
    final scheme = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Phone A — Gateway'),
        actions: [
          PopupMenuButton<String>(
            onSelected: (v) async {
              if (v == 'rotate') {
                final ok = await showDialog<bool>(
                  context: context,
                  builder: (c) => AlertDialog(
                    title: const Text('Make a new pairing code?'),
                    content: const Text('The currently paired phone will be disconnected and must scan the new code. '
                        'Do this if someone else may have seen the code.'),
                    actions: [
                      TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancel')),
                      FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('New code')),
                    ],
                  ),
                );
                if (ok == true) {
                  await Native.rotateKey();
                  _refresh();
                }
              }
              if (v == 'reset') {
                await Native.reset();
                widget.onReset();
              }
            },
            itemBuilder: (_) => const [
              PopupMenuItem(value: 'rotate', child: Text('New pairing code')),
              PopupMenuItem(value: 'reset', child: Text('Unpair & change mode')),
            ],
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          padding: const EdgeInsets.all(20),
          children: [
            Card(
              color: peer ? scheme.primaryContainer : scheme.surfaceContainerHighest,
              child: ListTile(
                leading: Icon(peer ? Icons.link : Icons.link_off),
                title: Text(!running ? 'Stopped' : peer ? 'Phone B connected' : 'Waiting for Phone B'),
                subtitle: Text('Wi-Fi address: ${_s['ip'] ?? 'not on Wi-Fi'}:${_s['port'] ?? ''}\n'
                    'Waiting to deliver: ${_s['pending'] ?? 0} SMS'),
                isThreeLine: true,
              ),
            ),
            if (!_batteryOk)
              Card(
                color: scheme.errorContainer,
                child: ListTile(
                  leading: const Icon(Icons.battery_alert),
                  title: const Text('Battery saver may stop forwarding'),
                  subtitle: const Text('Tap to allow Dual Link to run in the background.'),
                  onTap: Native.requestIgnoreBattery,
                ),
              ),
            const SizedBox(height: 8),
            const VoiceSetupCard(),
            const SizedBox(height: 16),
            if (qr != null) ...[
              Text('Pair Phone B', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              const Text('On Phone B, open Dual Link → "Phone B — no SIM" and scan this code. Keep it private: '
                  'anyone with this code on your Wi-Fi can read your SMS.'),
              const SizedBox(height: 16),
              Center(
                child: Container(
                  color: Colors.white,
                  padding: const EdgeInsets.all(12),
                  child: QrImageView(data: qr, size: 240),
                ),
              ),
              const SizedBox(height: 8),
              Center(
                child: TextButton.icon(
                  icon: const Icon(Icons.copy),
                  label: const Text('Copy pairing code'),
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: qr));
                    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Pairing code copied')));
                  },
                ),
              ),
            ] else
              const Text('Connect this phone to Wi-Fi to show the pairing code.'),
            const SizedBox(height: 24),
            enabled
                ? OutlinedButton.icon(
                    onPressed: () async { await Native.stop(); _refresh(); },
                    icon: const Icon(Icons.stop), label: const Text('Stop gateway'))
                : FilledButton.icon(
                    onPressed: () async { await Native.resume(); _refresh(); },
                    icon: const Icon(Icons.play_arrow), label: const Text('Start gateway')),
          ],
        ),
      ),
    );
  }
}
