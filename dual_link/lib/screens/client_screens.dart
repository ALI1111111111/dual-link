import 'dart:async';

import 'package:flutter/material.dart';

import '../contacts.dart';
import '../native.dart';
import 'calls_screen.dart' show dialViaPhoneA;

String _time(int ms) {
  final d = DateTime.fromMillisecondsSinceEpoch(ms);
  final now = DateTime.now();
  String two(int n) => n.toString().padLeft(2, '0');
  if (d.year == now.year && d.month == now.month && d.day == now.day) return '${two(d.hour)}:${two(d.minute)}';
  return '${d.day}/${d.month} ${two(d.hour)}:${two(d.minute)}';
}

/// Plain-language text for a failed send.
String _reasonText(String? r) => switch (r) {
      'network_rejected' => 'Network rejected it — check SIM balance / number',
      'no_service' => 'No signal on that SIM',
      'radio_off' => 'SIM is off (airplane mode?)',
      'blocked_by_phone' => 'Blocked by Phone A (SMS limit)',
      'invalid_number' => 'Invalid number',
      'invalid_body' => 'Message empty or too long',
      'rate_limited' => 'Too many messages — wait a minute',
      'sim_not_available' => 'That SIM is not available',
      null || '' => 'Failed',
      _ => 'Failed ($r)',
    };

String _simLabel(List sims, int id) {
  for (final s in sims) {
    if (s['id'] == id) return 'SIM ${s['slot']} · ${s['name']}';
  }
  return 'Default SIM';
}

class _Avatar extends StatelessWidget {
  const _Avatar(this.address);
  final String address;

  @override
  Widget build(BuildContext context) {
    final name = ContactBook.name(address);
    return CircleAvatar(
      child: name != null ? Text(name.characters.first.toUpperCase()) : const Icon(Icons.person),
    );
  }
}

/// Phone B home: connection status + conversation list.
class InboxScreen extends StatefulWidget {
  const InboxScreen({super.key, required this.onReset});
  final VoidCallback onReset;

  @override
  State<InboxScreen> createState() => _InboxScreenState();
}

class _InboxScreenState extends State<InboxScreen> with WidgetsBindingObserver {
  Map<String, dynamic> _s = {};
  List<Map<String, dynamic>> _threads = [];
  StreamSubscription? _sub;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
    ContactBook.load().then((_) => mounted ? setState(() {}) : null);
    _sub = Native.events.listen((_) => _refresh());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _sub?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  bool _isDefault = true;

  Future<void> _refresh() async {
    final s = await Native.state();
    final t = await Native.threads();
    final d = await Native.isDefaultSms();
    if (mounted) setState(() { _s = s; _threads = t; _isDefault = d; });
  }

  Future<void> _delete(String address) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('Delete conversation?'),
        content: Text('All messages with ${ContactBook.name(address) ?? address} will be deleted from this phone.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('Delete')),
        ],
      ),
    );
    if (ok == true) {
      await Native.deleteThread(address);
      _refresh();
    }
  }

  Future<void> _open(String address) async {
    await Navigator.push(context, MaterialPageRoute(builder: (_) => ThreadScreen(address: address)));
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final connected = _s['connected'] == true;
    final enabled = _s['enabled'] == true;
    final battery = _s['battery'];
    final scheme = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Messages'),
        actions: [
          PopupMenuButton<String>(
            onSelected: (v) async {
              if (v == 'privacy') await Native.openPrivacy();
              if (v == 'stop') await Native.stop();
              if (v == 'resume') await Native.resume();
              if (v == 'reset') {
                await Native.reset();
                widget.onReset();
                return;
              }
              _refresh();
            },
            itemBuilder: (_) => [
              enabled
                  ? const PopupMenuItem(value: 'stop', child: Text('Disconnect'))
                  : const PopupMenuItem(value: 'resume', child: Text('Connect')),
              const PopupMenuItem(value: 'reset', child: Text('Unpair & change mode')),
              const PopupMenuItem(value: 'privacy', child: Text('Privacy policy')),
            ],
          ),
        ],
      ),
      body: Column(
        children: [
          Material(
            color: connected ? scheme.primaryContainer : scheme.errorContainer,
            child: ListTile(
              dense: true,
              leading: Icon(connected ? Icons.link : Icons.link_off),
              title: Text(connected
                  ? 'Connected to Phone A${battery != null ? ' · battery $battery%' : ''}'
                  : enabled ? 'Looking for Phone A on Wi-Fi…' : 'Disconnected'),
            ),
          ),
          if (!_isDefault)
            Card(
              margin: const EdgeInsets.all(12),
              child: ListTile(
                leading: const Icon(Icons.sms),
                title: const Text('Make Dual Link your SMS app'),
                subtitle: const Text('Messages go into the phone\'s SMS inbox, and Contacts, links and other apps open Dual Link to text.'),
                trailing: FilledButton(onPressed: Native.requestDefaultSms, child: const Text('Set')),
              ),
            ),
          Expanded(
            child: _threads.isEmpty
                ? const Center(child: Text('No messages yet'))
                : RefreshIndicator(
                    onRefresh: _refresh,
                    child: ListView.separated(
                      itemCount: _threads.length,
                      separatorBuilder: (_, _) => const Divider(height: 1),
                      itemBuilder: (_, i) {
                        final t = _threads[i];
                        final unread = (t['unread'] as int) > 0;
                        final address = t['address'] as String;
                        return ListTile(
                          leading: _Avatar(address),
                          title: Text(ContactBook.name(address) ?? address,
                              style: TextStyle(fontWeight: unread ? FontWeight.bold : null)),
                          subtitle: Text(
                            '${t['dir'] == 1 ? 'You: ' : ''}${t['body']}',
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(fontWeight: unread ? FontWeight.w600 : null),
                          ),
                          trailing: Text(_time(t['ts'] as int)),
                          onTap: () => _open(address),
                          onLongPress: () => _delete(address),
                        );
                      },
                    ),
                  ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        icon: const Icon(Icons.edit),
        label: const Text('New'),
        onPressed: () async {
          final to = await Navigator.push<String>(context, MaterialPageRoute(builder: (_) => const ContactPickerScreen()));
          if (to != null && to.isNotEmpty) _open(to);
        },
      ),
    );
  }
}

/// Search contacts or type a number.
class ContactPickerScreen extends StatefulWidget {
  const ContactPickerScreen({super.key});

  @override
  State<ContactPickerScreen> createState() => _ContactPickerScreenState();
}

class _ContactPickerScreenState extends State<ContactPickerScreen> {
  final _q = TextEditingController();

  @override
  void initState() {
    super.initState();
    ContactBook.load().then((_) => mounted ? setState(() {}) : null);
    _q.addListener(() => setState(() {}));
  }

  @override
  Widget build(BuildContext context) {
    final q = _q.text.trim().toLowerCase();
    final typed = q.replaceAll(RegExp(r'[\s\-()]'), '');
    final isNumber = RegExp(r'^\+?\d{3,15}$').hasMatch(typed);
    final digits = typed.replaceAll('+', '');
    final list = ContactBook.all
        .where((c) => q.isEmpty || c['name']!.toLowerCase().contains(q) || (digits.isNotEmpty && c['number']!.contains(digits)))
        .toList();

    return Scaffold(
      appBar: AppBar(
        title: TextField(
          controller: _q,
          autofocus: true,
          decoration: const InputDecoration(hintText: 'Name or number', border: InputBorder.none),
        ),
      ),
      body: ListView(
        children: [
          if (isNumber)
            ListTile(
              leading: const CircleAvatar(child: Icon(Icons.dialpad)),
              title: Text('Send to $typed'),
              onTap: () => Navigator.pop(context, typed),
            ),
          if (ContactBook.all.isEmpty && !isNumber)
            const Padding(
              padding: EdgeInsets.all(24),
              child: Text('No contacts found on this phone. Type a number above.'),
            ),
          for (final c in list)
            ListTile(
              leading: _Avatar(c['number']!),
              title: Text(c['name']!),
              subtitle: Text(c['number']!),
              onTap: () => Navigator.pop(context, c['number']),
            ),
        ],
      ),
    );
  }
}

class ThreadScreen extends StatefulWidget {
  const ThreadScreen({super.key, required this.address, this.initialText});
  final String address;
  final String? initialText;

  @override
  State<ThreadScreen> createState() => _ThreadScreenState();
}

class _ThreadScreenState extends State<ThreadScreen> {
  final _input = TextEditingController();
  List<Map<String, dynamic>> _msgs = [];
  List _sims = [];
  int? _sim; // chosen SIM; null = not chosen yet (use default rule)
  StreamSubscription? _sub;

  @override
  void initState() {
    super.initState();
    if (widget.initialText != null) _input.text = widget.initialText!;
    ContactBook.load(ask: false).then((_) => mounted ? setState(() {}) : null);
    _refresh();
    _sub = Native.events.listen((e) {
      if (e['type'] == 'sms' || e['type'] == 'status' || e['type'] == 'client') _refresh();
    });
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final m = await Native.messages(widget.address);
    final s = await Native.state();
    await Native.markRead(widget.address);
    if (mounted) setState(() { _msgs = m; _sims = (s['sims'] as List?) ?? []; });
  }

  /// Default: the SIM this person last texted you on; otherwise Phone A's first SIM.
  int get _activeSim {
    if (_sim != null) return _sim!;
    for (final m in _msgs.reversed) {
      final id = m['sim'] as int;
      if (m['dir'] == 0 && _sims.any((s) => s['id'] == id)) return id;
    }
    return _sims.isNotEmpty ? _sims.first['id'] as int : -1;
  }

  Future<void> _send() async {
    final text = _input.text.trim();
    if (text.isEmpty) return;
    _input.clear();
    await Native.sendSms(widget.address, text, sim: _activeSim);
    _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final name = ContactBook.name(widget.address);
    final small = Theme.of(context).textTheme.labelSmall;
    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(name ?? widget.address),
            if (name != null) Text(widget.address, style: Theme.of(context).textTheme.bodySmall),
          ],
        ),
        actions: [
          if (RegExp(r'^\+?\d{3,15}$').hasMatch(widget.address))
            IconButton(tooltip: 'Call via Phone A', icon: const Icon(Icons.call), onPressed: () => dialViaPhoneA(context, widget.address)),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: ListView.builder(
              reverse: true,
              padding: const EdgeInsets.all(12),
              itemCount: _msgs.length,
              itemBuilder: (_, i) {
                final m = _msgs[_msgs.length - 1 - i];
                final mine = m['dir'] == 1;
                final failed = m['status'] == 'failed';
                final meta = [
                  _time(m['ts'] as int),
                  if (_sims.length > 1 && (m['sim'] as int) >= 0) 'SIM ${_simLabel(_sims, m['sim'] as int).split(' ')[1]}',
                  if (mine && !failed) m['status'],
                ].join(' · ');
                return Align(
                  alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
                  child: Container(
                    constraints: BoxConstraints(maxWidth: MediaQuery.sizeOf(context).width * 0.78),
                    margin: const EdgeInsets.symmetric(vertical: 4),
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                    decoration: BoxDecoration(
                      color: failed
                          ? scheme.errorContainer
                          : mine ? scheme.primaryContainer : scheme.surfaceContainerHighest,
                      borderRadius: BorderRadius.circular(18),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        SelectableText(m['body'] as String),
                        const SizedBox(height: 4),
                        Text(meta, style: small),
                        if (failed)
                          Text('⚠ ${_reasonText(m['reason'] as String?)}',
                              style: small?.copyWith(color: scheme.error, fontWeight: FontWeight.w600)),
                      ],
                    ),
                  ),
                );
              },
            ),
          ),
          if (_sims.length > 1)
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 4, 12, 0),
              child: SegmentedButton<int>(
                showSelectedIcon: false,
                segments: [
                  for (final s in _sims)
                    ButtonSegment(value: s['id'] as int, label: Text(_simLabel(_sims, s['id'] as int), overflow: TextOverflow.ellipsis)),
                ],
                selected: {_activeSim},
                onSelectionChanged: (v) => setState(() => _sim = v.first),
              ),
            ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
              child: Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _input,
                      minLines: 1,
                      maxLines: 5,
                      textCapitalization: TextCapitalization.sentences,
                      decoration: InputDecoration(
                        hintText: _sims.length == 1 ? 'Text message · ${_simLabel(_sims, _activeSim)}' : 'Text message',
                        border: const OutlineInputBorder(),
                      ),
                    ),
                  ),
                  IconButton.filled(onPressed: _send, icon: const Icon(Icons.send)),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
