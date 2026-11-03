part of 'main.dart';

/// Picker for the apps shown on the Dextop Book desktop home screen. The
/// selection is stored natively (BookStore "home_apps") so a running Book
/// session places the apps on the desktop grid immediately.
class HomeScreenAppsPage extends StatefulWidget {
  const HomeScreenAppsPage({required this.bridge, super.key});

  final NativeBridge bridge;

  @override
  State<HomeScreenAppsPage> createState() => _HomeScreenAppsPageState();
}

class _HomeScreenAppsPageState extends State<HomeScreenAppsPage> {
  final TextEditingController _query = TextEditingController();
  var _loading = true;
  var _saving = false;
  List<Map<String, dynamic>> _apps = [];
  var _selected = <String>{};
  Map<String, dynamic> _icons = {};

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  @override
  void dispose() {
    _query.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final results = await Future.wait<dynamic>([
        widget.bridge.appsMetadata(),
        widget.bridge.homeScreenApps(),
        NativeBridge.channel.invokeMapMethod<String, dynamic>('appIcons'),
      ]);
      if (!mounted) return;
      final rawApps = (results[0] as List<dynamic>).whereType<Map>().toList();
      setState(() {
        _apps = rawApps
            .map((item) => Map<String, dynamic>.from(item))
            .toList()
          ..sort(
            (a, b) =>
                '${a['label']}'.toLowerCase().compareTo('${b['label']}'.toLowerCase()),
          );
        _selected = (results[1] as List<dynamic>).whereType<String>().toSet();
        _icons = (results[2] as Map<String, dynamic>? ?? {});
        _loading = false;
      });
    } catch (_) {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _toggle(String package, bool value) async {
    setState(() {
      if (value) {
        _selected.add(package);
      } else {
        _selected.remove(package);
      }
      _saving = true;
    });
    try {
      final stored = await widget.bridge.setHomeScreenApps(_selected.toList());
      if (!mounted) return;
      setState(() {
        _selected = stored.toSet();
        _saving = false;
      });
    } catch (_) {
      if (mounted) {
        setState(() => _saving = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              localText(context, '保存できませんでした', 'Could not save the selection'),
            ),
          ),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    final needle = _query.text.trim().toLowerCase();
    final shown = needle.isEmpty
        ? _apps
        : _apps.where((app) {
            final label = '${app['label']}'.toLowerCase();
            final package = '${app['package']}'.toLowerCase();
            return label.contains(needle) || package.contains(needle);
          }).toList();
    return Scaffold(
      appBar: AppBar(
        title: Text(
          localText(context, 'ホーム画面のアプリ', 'Home screen apps'),
        ),
        actions: [
          if (_saving)
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 16),
              child: SizedBox.square(
                dimension: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: TextField(
              controller: _query,
              onChanged: (_) => setState(() {}),
              decoration: InputDecoration(
                prefixIcon: const Icon(Icons.search_rounded),
                hintText: localText(context, 'アプリを検索', 'Search apps'),
                suffixIcon: _query.text.isEmpty
                    ? null
                    : IconButton(
                        icon: const Icon(Icons.close_rounded),
                        onPressed: () {
                          _query.clear();
                          setState(() {});
                        },
                      ),
                border: const OutlineInputBorder(
                  borderRadius: BorderRadius.all(Radius.circular(28)),
                ),
                filled: true,
                fillColor: colors.surfaceContainerHighest,
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                inlineFormat(
                  localText(
                    context,
                    '{0} 件をホーム画面に表示します（Dextop Book）',
                    '{0} shown on the home screen (Dextop Book)',
                  ),
                  [_selected.length],
                ),
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                  color: colors.onSurfaceVariant,
                ),
              ),
            ),
          ),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : shown.isEmpty
                ? Center(
                    child: Text(
                      localText(context, 'アプリが見つかりません', 'No apps found'),
                      style: TextStyle(color: colors.onSurfaceVariant),
                    ),
                  )
                : ListView.separated(
                    itemCount: shown.length,
                    separatorBuilder: (_, _) => const Divider(height: 1),
                    itemBuilder: (context, index) {
                      final app = shown[index];
                      final package = '${app['package']}';
                      final icon = _icons[package];
                      return CheckboxListTile(
                        value: _selected.contains(package),
                        onChanged: (value) =>
                            _toggle(package, value ?? false),
                        secondary: icon is Uint8List
                            ? ClipRRect(
                                borderRadius: BorderRadius.circular(8),
                                child: Image.memory(
                                  icon,
                                  width: 40,
                                  height: 40,
                                ),
                              )
                            : const Icon(Icons.android_rounded),
                        title: Text('${app['label']}'),
                        subtitle: Text(
                          package,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
