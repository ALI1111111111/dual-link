import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../disclosure.dart';
import '../native.dart';

/// Phone B: scan Phone A's QR code (or paste the code) to pair.
class PairScreen extends StatefulWidget {
  const PairScreen({super.key});

  @override
  State<PairScreen> createState() => _PairScreenState();
}

class _PairScreenState extends State<PairScreen> {
  final _manual = TextEditingController();
  bool _busy = false;
  String? _error;

  bool _valid(String code) {
    try {
      final j = jsonDecode(code) as Map<String, dynamic>;
      return j['h'] is String && j['k'] is String;
    } catch (_) {
      return false;
    }
  }

  Future<void> _pair(String code) async {
    if (_busy) return;
    if (!_valid(code)) {
      setState(() => _error = 'That is not a Dual Link pairing code.');
      return;
    }
    setState(() => _busy = true);
    if (!await showDataDisclosure(context, phoneA: false)) {
      setState(() => _busy = false);
      return;
    }
    await Native.requestPermissions(['notifications']);
    await Native.pairClient(code);
    if (!await Native.isIgnoringBattery()) await Native.requestIgnoreBattery();
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Pair with Phone A')),
      body: ListView(
        padding: const EdgeInsets.all(20),
        children: [
          const Text('Both phones must be on the same Wi-Fi. Scan the code shown on Phone A.'),
          const SizedBox(height: 16),
          ClipRRect(
            borderRadius: BorderRadius.circular(16),
            child: SizedBox(
              height: 300,
              child: MobileScanner(
                onDetect: (capture) {
                  final v = capture.barcodes.firstOrNull?.rawValue;
                  if (v != null) _pair(v);
                },
              ),
            ),
          ),
          const SizedBox(height: 24),
          const Text('Or paste the pairing code:'),
          const SizedBox(height: 8),
          TextField(
            controller: _manual,
            minLines: 2,
            maxLines: 4,
            decoration: const InputDecoration(border: OutlineInputBorder(), hintText: '{"h":"192.168…'),
          ),
          if (_error != null) ...[
            const SizedBox(height: 8),
            Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          ],
          const SizedBox(height: 12),
          FilledButton(onPressed: _busy ? null : () => _pair(_manual.text.trim()), child: const Text('Pair')),
        ],
      ),
    );
  }
}
