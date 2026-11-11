import 'dart:async';

import 'package:flutter/material.dart';

import '../contacts.dart';
import '../native.dart';
import 'client_screens.dart';

/// Which tab Phone B shows (0 = Messages, 1 = Calls). Set from notifications / tel: links.
final clientTab = ValueNotifier<int>(0);

/// Phone B home: Messages + Calls, with a call banner on top while Phone A is ringing / on a call.
class ClientHome extends StatefulWidget {
  const ClientHome({super.key, required this.onReset});
  final VoidCallback onReset;

  @override
  State<ClientHome> createState() => _ClientHomeState();
}

class _ClientHomeState extends State<ClientHome> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _askPermissions());
  }

  /// Everything Phone B needs, asked once at start (Android only shows what's still missing).
  Future<void> _askPermissions() async {
    await Native.requestPermissions(['notifications']);
    await ContactBook.load();
    if (!await Native.canFullScreen() && mounted) {
      final ok = await showDialog<bool>(
        context: context,
        builder: (c) => AlertDialog(
          title: const Text('Show calls full screen?'),
          content: const Text('So calls on Phone A ring on this phone like a normal call, even when it is locked. '
              'Turn on "Allow full screen notifications" for Dual Link.'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Later')),
            FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('Open settings')),
          ],
        ),
      );
      if (ok == true) await Native.openFullScreenSettings();
    }
  }

  @override
  Widget build(BuildContext context) {
    final onReset = widget.onReset;
    return ValueListenableBuilder<int>(
      valueListenable: clientTab,
      builder: (_, tab, _) => Scaffold(
        body: Column(
          children: [
            const SafeArea(bottom: false, child: CallBanner()),
            Expanded(
              child: IndexedStack(index: tab, children: [InboxScreen(onReset: onReset), const CallsScreen()]),
            ),
          ],
        ),
        bottomNavigationBar: NavigationBar(
          selectedIndex: tab,
          onDestinationSelected: (i) => clientTab.value = i,
          destinations: const [
            NavigationDestination(icon: Icon(Icons.sms_outlined), selectedIcon: Icon(Icons.sms), label: 'Messages'),
            NavigationDestination(icon: Icon(Icons.call_outlined), selectedIcon: Icon(Icons.call), label: 'Calls'),
          ],
        ),
      ),
    );
  }
}

String _simShort(List sims, int id) {
  for (final s in sims) {
    if (s['id'] == id) return 'SIM ${s['slot']} · ${s['name']}';
  }
  return 'Phone A';
}

/// Shows a ringing / active call on Phone A with Decline, Answer on Phone A, Message, End.
class CallBanner extends StatefulWidget {
  const CallBanner({super.key});

  @override
  State<CallBanner> createState() => _CallBannerState();
}

class _CallBannerState extends State<CallBanner> {
  Map<String, dynamic>? _call;
  Map<String, dynamic> _voice = {};
  List _sims = [];
  StreamSubscription? _sub;
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    _refresh();
    _sub = Native.events.listen((e) {
      if (e['type'] == 'call' || e['type'] == 'client' || e['type'] == 'voice') _refresh();
      if (e['type'] == 'callcmd' && e['ok'] != true && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Phone A could not ${e['cmd']}: ${e['reason']}')));
      }
    });
    _tick = Timer.periodic(const Duration(seconds: 1), (_) { if (_call?['state'] == 'active') setState(() {}); });
  }

  @override
  void dispose() {
    _sub?.cancel();
    _tick?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final s = await Native.state();
    if (mounted) {
      setState(() {
        _call = s['call'] == null ? null : Map<String, dynamic>.from(s['call'] as Map);
        _voice = Map<String, dynamic>.from((s['voice'] as Map?) ?? {});
        _sims = (s['sims'] as List?) ?? [];
      });
    }
  }

  Future<void> _cmd(String c) async {
    if (!await Native.callCmd(c) && mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Phone A is not connected')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = _call;
    if (c == null) return const SizedBox.shrink();
    final number = (c['number'] as String?) ?? '';
    final who = ContactBook.name(number) ?? (number.isEmpty ? 'Unknown number' : number);
    final ringing = c['state'] == 'ringing';
    final scheme = Theme.of(context).colorScheme;
    final voiceReady = _voice['helper'] != null && _voice['helper'] != 'not_started';
    final talking = _voice['active'] == true;
    final secs = ((DateTime.now().millisecondsSinceEpoch - (c['since'] as int)) / 1000).floor();
    final dur = '${(secs ~/ 60).toString().padLeft(2, '0')}:${(secs % 60).toString().padLeft(2, '0')}';

    return Material(
      color: ringing ? scheme.tertiaryContainer : scheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              Icon(ringing ? Icons.ring_volume : Icons.call),
              const SizedBox(width: 12),
              Expanded(
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text(who, style: Theme.of(context).textTheme.titleMedium),
                  Text(ringing
                      ? 'Incoming call · ${_simShort(_sims, c['sim'] as int)}'
                      : talking ? 'On call · talking on this phone · $dur' : 'On call on Phone A · $dur'),
                  if (!voiceReady)
                    Text('Talk on Phone A (talking from this phone is off — Phone A → Advanced)',
                        style: Theme.of(context).textTheme.bodySmall),
                ]),
              ),
            ]),
            const SizedBox(height: 8),
            Wrap(spacing: 8, runSpacing: 4, children: [
              if (ringing) ...[
                FilledButton.icon(
                  style: FilledButton.styleFrom(backgroundColor: scheme.error, foregroundColor: scheme.onError),
                  onPressed: () => _cmd('decline'), icon: const Icon(Icons.call_end), label: const Text('Decline')),
                if (voiceReady)
                  FilledButton.icon(
                    style: FilledButton.styleFrom(backgroundColor: Colors.green.shade700, foregroundColor: Colors.white),
                    onPressed: () => answerHere(context), icon: const Icon(Icons.call), label: const Text('Answer here')),
                OutlinedButton.icon(onPressed: () => _cmd('answer'), icon: const Icon(Icons.phone_android), label: const Text('Answer on Phone A')),
              ] else ...[
                FilledButton.icon(
                  style: FilledButton.styleFrom(backgroundColor: scheme.error, foregroundColor: scheme.onError),
                  onPressed: () => _cmd('end'), icon: const Icon(Icons.call_end), label: const Text('End call')),
                if (talking) ...[
                  IconButton.filledTonal(
                    tooltip: 'Speaker', isSelected: _voice['speaker'] == true,
                    onPressed: () async { await Native.voiceSpeaker(_voice['speaker'] != true); _refresh(); },
                    icon: const Icon(Icons.volume_up)),
                  IconButton.filledTonal(
                    tooltip: 'Mute', isSelected: _voice['muted'] == true,
                    onPressed: () async { await Native.voiceMute(_voice['muted'] != true); _refresh(); },
                    icon: Icon(_voice['muted'] == true ? Icons.mic_off : Icons.mic)),
                ] else if (voiceReady)
                  FilledButton.icon(onPressed: () async { await talkHere(context); _refresh(); },
                      icon: const Icon(Icons.record_voice_over), label: const Text('Talk here')),
              ],
              if (number.isNotEmpty)
                OutlinedButton.icon(
                  onPressed: () {
                    if (ringing) _cmd('decline');
                    Navigator.push(context, MaterialPageRoute(builder: (_) => ThreadScreen(
                        address: number, initialText: ringing ? "Can't talk right now. I'll call you back." : null)));
                  },
                  icon: const Icon(Icons.sms), label: Text(ringing ? 'Decline + SMS' : 'Message')),
            ]),
          ],
        ),
      ),
    );
  }
}

/// Starts two-way audio on this phone for Phone A's current call.
Future<bool> talkHere(BuildContext context) async {
  if (!await Native.requestPermissions(['mic'])) {
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Microphone permission is needed to talk')));
    }
    return false;
  }
  final ok = await Native.voiceStart();
  if (!ok && context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Could not start audio')));
  }
  return ok;
}

/// Answers the ringing call on Phone A and talks on this phone.
Future<void> answerHere(BuildContext context) async {
  if (!await Native.requestPermissions(['mic'])) return;
  if (!await Native.callCmd('answer')) {
    if (context.mounted) ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Phone A is not connected')));
    return;
  }
  if (context.mounted) await talkHere(context);
}

/// Asks which SIM, then makes Phone A dial; audio comes to this phone when the voice helper runs.
Future<void> dialViaPhoneA(BuildContext context, String number) async {
  final s = await Native.state();
  final sims = (s['sims'] as List?) ?? [];
  if (!context.mounted) return;
  final who = ContactBook.name(number);
  final sim = await showModalBottomSheet<int>(
    context: context,
    builder: (c) => SafeArea(
      child: Column(mainAxisSize: MainAxisSize.min, children: [
        ListTile(
          title: Text('Call ${who ?? number}'),
          subtitle: Text('${who != null ? '$number · ' : ''}Phone A places the call; talk on Phone A.'),
        ),
        if (sims.isEmpty)
          ListTile(leading: const Icon(Icons.call), title: const Text('Call from Phone A'), onTap: () => Navigator.pop(c, -1)),
        for (final x in sims)
          ListTile(
            leading: const Icon(Icons.sim_card),
            title: Text('Call with SIM ${x['slot']} · ${x['name']}'),
            onTap: () => Navigator.pop(c, x['id'] as int),
          ),
      ]),
    ),
  );
  if (sim == null) return;
  final ok = await Native.callCmd('dial', number: number, sim: sim);
  if (!ok && context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Phone A is not connected')));
    return;
  }
  // Talk on this phone if Phone A's voice helper is running.
  final v = (s['voice'] as Map?) ?? {};
  if (v['helper'] != 'not_started' && context.mounted) await talkHere(context);
}

/// Phone A's call history with names, SIM and quick call / message.
class CallsScreen extends StatefulWidget {
  const CallsScreen({super.key});

  @override
  State<CallsScreen> createState() => _CallsScreenState();
}

class _CallsScreenState extends State<CallsScreen> {
  List<Map<String, dynamic>> _calls = [];
  List _sims = [];
  StreamSubscription? _sub;

  @override
  void initState() {
    super.initState();
    _refresh();
    ContactBook.load(ask: false).then((_) => mounted ? setState(() {}) : null);
    _sub = Native.events.listen((e) { if (e['type'] == 'calls' || e['type'] == 'call') _refresh(); });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final c = await Native.calls();
    final s = await Native.state();
    if (mounted) setState(() { _calls = c; _sims = (s['sims'] as List?) ?? []; });
  }

  static (IconData, String) _kind(int type) => switch (type) {
        1 => (Icons.call_received, 'Incoming'),
        2 => (Icons.call_made, 'Outgoing'),
        3 => (Icons.call_missed, 'Missed'),
        5 => (Icons.call_end, 'Declined'),
        6 => (Icons.block, 'Blocked'),
        _ => (Icons.call, 'Call'),
      };

  String _when(int ms) {
    final d = DateTime.fromMillisecondsSinceEpoch(ms);
    final now = DateTime.now();
    String two(int n) => n.toString().padLeft(2, '0');
    final t = '${two(d.hour)}:${two(d.minute)}';
    if (d.year == now.year && d.month == now.month && d.day == now.day) return t;
    return '${d.day}/${d.month} $t';
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      appBar: AppBar(title: const Text('Calls')),
      body: _calls.isEmpty
          ? const Center(child: Text('No calls yet'))
          : RefreshIndicator(
              onRefresh: _refresh,
              child: ListView.builder(
                itemCount: _calls.length,
                itemBuilder: (_, i) {
                  final c = _calls[i];
                  final number = c['number'] as String;
                  final (icon, label) = _kind(c['type'] as int);
                  final bad = c['type'] == 3 || c['type'] == 5;
                  final dur = c['dur'] as int;
                  return ListTile(
                    leading: Icon(icon, color: bad ? scheme.error : null),
                    title: Text(ContactBook.name(number) ?? (number.isEmpty ? 'Unknown' : number),
                        style: TextStyle(color: bad ? scheme.error : null)),
                    subtitle: Text([
                      label,
                      if (dur > 0) '${dur ~/ 60}m ${dur % 60}s',
                      if (_sims.length > 1) _simShort(_sims, c['sim'] as int).split(' · ').first,
                      _when(c['ts'] as int),
                    ].join(' · ')),
                    trailing: number.isEmpty
                        ? null
                        : Row(mainAxisSize: MainAxisSize.min, children: [
                            IconButton(
                              tooltip: 'Message',
                              icon: const Icon(Icons.sms_outlined),
                              onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => ThreadScreen(address: number))),
                            ),
                            IconButton(tooltip: 'Call via Phone A', icon: const Icon(Icons.call), onPressed: () => dialViaPhoneA(context, number)),
                          ]),
                  );
                },
              ),
            ),
      floatingActionButton: FloatingActionButton.extended(
        icon: const Icon(Icons.dialpad),
        label: const Text('Call'),
        onPressed: () async {
          final to = await Navigator.push<String>(context, MaterialPageRoute(builder: (_) => const ContactPickerScreen()));
          if (to != null && to.isNotEmpty && context.mounted) dialViaPhoneA(context, to);
        },
      ),
    );
  }
}
