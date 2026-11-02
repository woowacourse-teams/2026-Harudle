import * as SplashScreen from 'expo-splash-screen';
import { useEffect, useRef } from 'react';
import { BackHandler, Platform } from 'react-native';
import { WebView } from 'react-native-webview';

export default function WebViewScreen() {
  // WebView 14.0.1의 기본 제네릭(undefined)이 props를 never로 만드는 타입 오류를 피한다.
  const webViewRef = useRef<WebView<object>>(null);
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
    <WebView<object>
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
