import {
  createContext,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from 'react';

type PwaInstallStatus =
  'unavailable' | 'installable' | 'installed' | 'ios-guide';

// beforeinstallprompt는 표준 DOM 타입에 포함되어 있지 않아 필요한 값만 정의한다.
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

interface PwaInstallContextValue {
  status: PwaInstallStatus;
  install: () => Promise<void>;
}

const PwaInstallContext = createContext<PwaInstallContextValue | null>(null);

// 일반 브라우저의 display-mode와 iOS 전용 standalone 값을 모두 확인한다.
export const isPwaInstalled = () => {
  const navigatorWithStandalone = navigator as Navigator & {
    standalone?: boolean;
  };

  return (
    window.matchMedia('(display-mode: standalone)').matches ||
    navigatorWithStandalone.standalone === true
  );
};

// iPadOS는 데스크톱 사이트에서 Mac으로 표시될 수 있어 터치 지원 여부도 확인한다.
const isIos = () => {
  return (
    /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1)
  );
};

export const PwaInstallProvider = ({ children }: { children: ReactNode }) => {
  // 브라우저가 전달한 설치 이벤트를 사용자가 버튼을 누를 때까지 보관한다.
  const [installPrompt, setInstallPrompt] =
    useState<BeforeInstallPromptEvent | null>(null);
  const [status, setStatus] = useState<PwaInstallStatus>(() => {
    // 이미 앱으로 실행 중이면 설치 버튼을 노출하지 않는다.
    if (isPwaInstalled()) {
      return 'installed';
    }

    // iOS는 설치창을 직접 열 수 없으므로 별도의 설치 안내를 보여준다.
    return isIos() ? 'ios-guide' : 'unavailable';
  });

  useEffect(() => {
    const handleBeforeInstallPrompt = (event: Event) => {
      // 브라우저의 기본 안내를 막고 설정 페이지 버튼에서 설치창을 연다.
      event.preventDefault();
      setInstallPrompt(event as BeforeInstallPromptEvent);
      setStatus('installable');
    };

    const handleAppInstalled = () => {
      // 주소창 등 다른 경로로 설치해도 설정 페이지 버튼을 숨긴다.
      setInstallPrompt(null);
      setStatus('installed');
    };

    window.addEventListener('beforeinstallprompt', handleBeforeInstallPrompt);
    window.addEventListener('appinstalled', handleAppInstalled);

    return () => {
      window.removeEventListener(
        'beforeinstallprompt',
        handleBeforeInstallPrompt,
      );
      window.removeEventListener('appinstalled', handleAppInstalled);
    };
  }, []);

  const install = async () => {
    if (!installPrompt) {
      return;
    }

    // 저장한 이벤트는 한 번만 사용할 수 있으므로 선택 후 비워준다.
    await installPrompt.prompt();
    const { outcome } = await installPrompt.userChoice;

    setInstallPrompt(null);
    setStatus(outcome === 'accepted' ? 'installed' : 'unavailable');
  };

  return (
    <PwaInstallContext.Provider value={{ status, install }}>
      {children}
    </PwaInstallContext.Provider>
  );
};

export const usePwaInstall = () => {
  const context = useContext(PwaInstallContext);

  if (!context) {
    throw new Error(
      'usePwaInstall은 PwaInstallProvider 내부에서만 사용할 수 있습니다.',
    );
  }

  return context;
};
