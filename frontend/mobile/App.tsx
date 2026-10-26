import { SafeAreaView } from 'react-native-safe-area-context';
import { WebView } from 'react-native-webview';

export default function App() {
  return (
    <SafeAreaView style={{ flex: 1 }}>
      <WebView
        style={{ flex: 1 }}
        source={{ uri: 'https://www.harudle.com/' }}
        allowsBackForwardNavigationGestures
      />
    </SafeAreaView>
  );
}
