import * as SplashScreen from 'expo-splash-screen';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';
import WebViewScreen from './WebviewScreen';

// 컴포넌트 밖에서 최대한 일찍 실행한다.
void SplashScreen.preventAutoHideAsync().catch((error: unknown): void => {
  console.warn('스플래시 자동 숨김 방지에 실패했어요.', error);
});

export default function App() {
  return (
    <SafeAreaProvider>
      <SafeAreaView style={{ flex: 1 }}>
        <WebViewScreen />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}
