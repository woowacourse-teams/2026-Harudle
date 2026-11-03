import * as SplashScreen from 'expo-splash-screen';
import { useEffect, useRef } from 'react';
import { BackHandler, Platform } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';
import { handleWebViewMessage } from './bridge/handleWebViewMessage';

const WEB_URL = 'https://www.harudle.com/';
const WEB_ORIGIN = new URL(WEB_URL).origin;

export default function WebViewScreen() {
  // WebView 14.0.1의 기본 제네릭(undefined)이 props를 never로 만드는 타입 오류를 피한다.
  const webViewRef = useRef<WebView<object>>(null);
  const canGoBackRef = useRef(false);

  const handleLoad = (): void => {
    SplashScreen.hide();
  };

  const sendMessage = (message: {
    readonly type: string;
    readonly payload: unknown;
    readonly id?: string;
  }): void => {
    webViewRef.current?.injectJavaScript(`
      if (window.location.origin === ${JSON.stringify(WEB_ORIGIN)}) {
        window.dispatchEvent(
          new CustomEvent('harudle:message', {
            detail: ${JSON.stringify(message)}
          })
        );
      }
      true;
    `);
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

  const APP_CONTEXT_SCRIPT = `
  window.isHarudleApp = true;
  true;
`;

  return (
    <WebView<object>
      ref={webViewRef}
      style={{ flex: 1 }}
      source={{ uri: WEB_URL }}
      onMessage={(event: WebViewMessageEvent): void => {
        void handleWebViewMessage(
          event,
          (result): void =>
            sendMessage({ type: 'auth.apple.signInResult', payload: result }),
          sendMessage,
        );
      }}
      onLoad={handleLoad}
      onNavigationStateChange={(state) => {
        canGoBackRef.current = state.canGoBack;
      }}
      injectedJavaScriptBeforeContentLoaded={APP_CONTEXT_SCRIPT}
      injectedJavaScript={APP_CONTEXT_SCRIPT}
      allowsBackForwardNavigationGestures
    />
  );
}
