import { WebView } from 'react-native-webview';

export default function App() {
  return (
    <WebView style={{ flex: 1 }} source={{ uri: 'https://www.harudle.com/' }} />
  );
}
