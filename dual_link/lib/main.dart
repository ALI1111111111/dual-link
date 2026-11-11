import 'package:flutter/material.dart';

import 'native.dart';
import 'screens/calls_screen.dart';
import 'screens/client_screens.dart';
import 'screens/gateway_screen.dart';
import 'screens/pair_screen.dart';

final navKey = GlobalKey<NavigatorState>();

/// Opens a conversation, optionally with text already typed (e.g. from an sms: link).
void openThread(String address, String? body) {
  navKey.currentState?.push(MaterialPageRoute(builder: (_) => ThreadScreen(address: address, initialText: body)));
}

/// Routes a request from a notification, Contacts, an sms:/tel: link. {action: sms|call|calls, address, body}
void openRequest(Map<String, String?> r) {
  final address = r['address'];
  switch (r['action']) {
    case 'answerHere':
      navKey.currentState?.popUntil((route) => route.isFirst);
      clientTab.value = 1;
      final ctx = navKey.currentContext;
      if (ctx != null) answerHere(ctx);
    case 'calls':
      navKey.currentState?.popUntil((route) => route.isFirst);
      clientTab.value = 1;
    case 'call':
      final ctx = navKey.currentContext;
      if (address != null && ctx != null) {
        clientTab.value = 1;
        dialViaPhoneA(ctx, address);
      }
    default:
      if (address != null) openThread(address, r['body']);
  }
}

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  Native.onOpen(openRequest);
  runApp(const DualLinkApp());
}

class DualLinkApp extends StatelessWidget {
  const DualLinkApp({super.key});

  @override
  Widget build(BuildContext context) {
    const seed = Color(0xFF0B7A75);
    return MaterialApp(
      title: 'Dual Link',
      navigatorKey: navKey,
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: seed, useMaterial3: true),
      darkTheme: ThemeData(colorSchemeSeed: seed, brightness: Brightness.dark, useMaterial3: true),
      home: const RootScreen(),
    );
  }
}

/// Picks the screen based on the saved mode.
class RootScreen extends StatefulWidget {
  const RootScreen({super.key});

  @override
  State<RootScreen> createState() => _RootScreenState();
}

class _RootScreenState extends State<RootScreen> {
  String? _mode;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    var s = await Native.state();
    // Self-heal: if forwarding is switched on but the background service isn't running, start it.
    final running = s['mode'] == 'gateway' ? s['gatewayRunning'] == true : s['clientRunning'] == true;
    if (s['enabled'] == true && s['mode'] != null && !running) s = await Native.resume();
    setState(() {
      _mode = s['mode'] as String?;
      _loading = false;
    });
    // Launched from a notification, a contact's "Message" button or an sms: link.
    if (_mode == 'client') {
      final c = await Native.takeLaunchCompose();
      if (c != null) WidgetsBinding.instance.addPostFrameCallback((_) => openRequest(c));
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Scaffold(body: Center(child: CircularProgressIndicator()));
    switch (_mode) {
      case 'gateway':
        return GatewayScreen(onReset: _load);
      case 'client':
        return ClientHome(onReset: _load);
      default:
        return ModeSelectScreen(onChosen: _load);
    }
  }
}

class ModeSelectScreen extends StatelessWidget {
  const ModeSelectScreen({super.key, required this.onChosen});
  final VoidCallback onChosen;

  @override
  Widget build(BuildContext context) {
    final t = Theme.of(context).textTheme;
    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            const SizedBox(height: 24),
            Text('Dual Link', style: t.headlineMedium),
            const SizedBox(height: 8),
            Text('Use one phone\'s SIM from another phone over Wi-Fi. Which phone is this?', style: t.bodyLarge),
            const SizedBox(height: 32),
            _ModeCard(
              icon: Icons.sim_card,
              title: 'Phone A — has the SIM',
              text: 'Receives and sends SMS for the other phone. Keep it on Wi-Fi and charged.',
              onTap: () async {
                await Navigator.push(context, MaterialPageRoute(builder: (_) => const GatewaySetupScreen()));
                onChosen();
              },
            ),
            const SizedBox(height: 16),
            _ModeCard(
              icon: Icons.smartphone,
              title: 'Phone B — no SIM',
              text: 'Shows your SMS and lets you reply through Phone A.',
              onTap: () async {
                await Navigator.push(context, MaterialPageRoute(builder: (_) => const PairScreen()));
                onChosen();
              },
            ),
          ],
        ),
      ),
    );
  }
}

class _ModeCard extends StatelessWidget {
  const _ModeCard({required this.icon, required this.title, required this.text, required this.onTap});
  final IconData icon;
  final String title;
  final String text;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Card(
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Row(
            children: [
              Icon(icon, size: 40, color: Theme.of(context).colorScheme.primary),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(title, style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 4),
                    Text(text),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
