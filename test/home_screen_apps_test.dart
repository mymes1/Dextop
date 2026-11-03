import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:free_dextop/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('app.freedextop/display');

  group('HomeScreenAppsPage', () {
    late List<List<String>> savedSelections;

    setUp(() {
      savedSelections = [];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
        switch (call.method) {
          case 'appsMetadata':
            return [
              {'label': 'Browser', 'package': 'com.example.browser'},
              {'label': 'Settings', 'package': 'com.android.settings'},
            ];
          case 'homeScreenApps':
            return ['com.android.settings'];
          case 'appIcons':
            return <String, dynamic>{};
          case 'setHomeScreenApps':
            final packages =
                ((call.arguments as Map)['packages'] as List).cast<String>();
            savedSelections.add(packages.toList());
            return packages;
        }
        return null;
      });
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, null);
    });

    testWidgets('lists all apps with the stored selection pre-checked', (
      tester,
    ) async {
      await tester.pumpWidget(
        MaterialApp(home: HomeScreenAppsPage(bridge: NativeBridge())),
      );
      await tester.pumpAndSettle();

      expect(find.text('Browser'), findsOneWidget);
      expect(find.text('Settings'), findsOneWidget);

      CheckboxListTile tileOf(String label) => tester.widget<CheckboxListTile>(
        find.ancestor(of: find.text(label), matching: find.byType(CheckboxListTile)),
      );
      expect(tileOf('Settings').value, isTrue);
      expect(tileOf('Browser').value, isFalse);
    });

    testWidgets('toggling an app saves the new selection to the native side', (
      tester,
    ) async {
      await tester.pumpWidget(
        MaterialApp(home: HomeScreenAppsPage(bridge: NativeBridge())),
      );
      await tester.pumpAndSettle();

      final browserTile = find.ancestor(
        of: find.text('Browser'),
        matching: find.byType(CheckboxListTile),
      );
      await tester.tap(browserTile);
      await tester.pumpAndSettle();

      expect(savedSelections, isNotEmpty);
      final last = savedSelections.last;
      expect(last, contains('com.example.browser'));
      expect(last, contains('com.android.settings'));

      // Toggling Settings off removes it from the saved selection.
      final settingsTile = find.ancestor(
        of: find.text('Settings'),
        matching: find.byType(CheckboxListTile),
      );
      await tester.tap(settingsTile);
      await tester.pumpAndSettle();
      expect(savedSelections.last, isNot(contains('com.android.settings')));
      expect(savedSelections.last, contains('com.example.browser'));
    });

    testWidgets('search filters the app list', (tester) async {
      await tester.pumpWidget(
        MaterialApp(home: HomeScreenAppsPage(bridge: NativeBridge())),
      );
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), 'browser');
      await tester.pumpAndSettle();

      expect(find.text('Browser'), findsOneWidget);
      expect(find.text('Settings'), findsNothing);
    });
  });
}
