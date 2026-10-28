import * as SplashScreen from 'expo-splash-screen';
import { useEffect, useRef } from 'react';
import { BackHandler, Platform } from 'react-native';
import { WebView } from 'react-native-webview';

export default function WebViewScreen() {
  const webViewRef = useRef<WebView>(null);
  const canGoBackRef = useRef(false);

  const handleLoad = (): void => {
    SplashScreen.hide();
  };

  useEffect(() => {
    if (Platform.OS !== 'android') return;

    const subscription = BackHandler.addEventListener(
      'hardwareBackPress',
      () => {
        if (canGoBackRef.current && webViewRef.current) {
          webViewRef.current.goBack();
          return true; // 이벤트 처리 완료
        }

        return false; // 기본 뒤로가기 동작
      },
    );

    return () => subscription.remove();
  }, []);

  return (
    <WebView
      ref={webViewRef}
      style={{ flex: 1 }}
      source={{ uri: 'https://www.harudle.com/' }}
      onLoad={handleLoad}
      onNavigationStateChange={(state) => {
        canGoBackRef.current = state.canGoBack;
      }}
      allowsBackForwardNavigationGestures
    />
  );
}
