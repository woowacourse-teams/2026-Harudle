import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import WebViewScreen from './WebviewScreen';

export default function App() {
  return (
    <SafeAreaProvider>
      <SafeAreaView style={{ flex: 1 }}>
        <WebViewScreen />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}
