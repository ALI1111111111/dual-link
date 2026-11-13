import 'package:flutter/material.dart';

/// Google Play "prominent disclosure": says exactly what personal data Dual Link touches, why,
/// and where it goes, right before Android's permission prompts. Returns true only if the user agrees.
Future<bool> showDataDisclosure(BuildContext context, {required bool phoneA}) async {
  final items = phoneA
      ? const [
          (Icons.sms, 'SMS messages',
              'Reads incoming SMS and your SMS history, and sends the replies you write on Phone B, from this SIM.'),
          (Icons.call, 'Calls and call log',
              'Sees incoming calls, the caller\'s number and your call history so Phone B can show them; '
                  'answers, declines or dials only when you tap it on Phone B.'),
        ]
      : const [
          (Icons.contacts, 'Contacts',
              'Shows names instead of numbers and lets you pick who to text. Stays on this phone.'),
          (Icons.sms, 'SMS messages',
              'As your default SMS app, stores the messages Phone A forwards and writes them to this phone\'s inbox.'),
          (Icons.mic, 'Microphone (only if you use "Talk here")',
              'Carries your voice to the call on Phone A while you talk. Never recorded.'),
        ];
  final ok = await showDialog<bool>(
    context: context,
    barrierDismissible: false,
    builder: (d) => AlertDialog(
      title: const Text('What Dual Link uses'),
      content: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (final (icon, title, text) in items)
              ListTile(
                contentPadding: EdgeInsets.zero,
                leading: Icon(icon),
                title: Text(title),
                subtitle: Text(text),
              ),
            const SizedBox(height: 8),
            const Text(
              'This data travels only between your two paired phones, encrypted, over your own Wi-Fi. '
              'Dual Link has no servers and no accounts: nothing is uploaded, sold or shared with anyone.',
            ),
          ],
        ),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(d, false), child: const Text('Not now')),
        FilledButton(onPressed: () => Navigator.pop(d, true), child: const Text('Agree & continue')),
      ],
    ),
  );
  return ok == true;
}
