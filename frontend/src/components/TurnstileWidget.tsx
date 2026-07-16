import { useEffect, useId, useRef } from 'react';

declare global {
  interface Window {
    turnstile?: {
      render: (selector: string | HTMLElement, options: {
        sitekey: string;
        action: string;
        callback: (token: string) => void;
        'expired-callback'?: () => void;
        'error-callback'?: () => void;
      }) => string;
      reset: (widgetId?: string) => void;
      remove?: (widgetId: string) => void;
    };
  }
}

let turnstileScriptPromise: Promise<void> | null = null;

function loadTurnstileScript(): Promise<void> {
  if (window.turnstile) return Promise.resolve();
  if (!turnstileScriptPromise) {
    turnstileScriptPromise = new Promise((resolve, reject) => {
      const existing = document.querySelector<HTMLScriptElement>('script[data-turnstile-script="true"]');
      if (existing) {
        existing.addEventListener('load', () => resolve(), { once: true });
        existing.addEventListener('error', () => reject(new Error('Failed to load Turnstile')), { once: true });
        return;
      }
      const script = document.createElement('script');
      script.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
      script.async = true;
      script.defer = true;
      script.dataset.turnstileScript = 'true';
      script.onload = () => resolve();
      script.onerror = () => reject(new Error('Failed to load Turnstile'));
      document.head.appendChild(script);
    });
  }
  return turnstileScriptPromise;
}

interface Props {
  visible: boolean;
  siteKey: string;
  action: string;
  resetSignal: number;
  onTokenChange: (token: string | null) => void;
  onStatusChange: (status: 'idle' | 'loading' | 'ready' | 'verified' | 'expired' | 'error') => void;
}

export default function TurnstileWidget({
  visible,
  siteKey,
  action,
  resetSignal,
  onTokenChange,
  onStatusChange,
}: Props) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const widgetIdRef = useRef<string | null>(null);
  const widgetKey = useId();

  useEffect(() => {
    if (!visible || !siteKey || !containerRef.current) {
      return undefined;
    }
    let active = true;
    onStatusChange('loading');
    loadTurnstileScript()
      .then(() => {
        if (!active || !containerRef.current || !window.turnstile || widgetIdRef.current) return;
        widgetIdRef.current = window.turnstile.render(containerRef.current, {
          sitekey: siteKey,
          action,
          callback: (token) => {
            onTokenChange(token);
            onStatusChange('verified');
          },
          'expired-callback': () => {
            onTokenChange(null);
            onStatusChange('expired');
          },
          'error-callback': () => {
            onTokenChange(null);
            onStatusChange('error');
          },
        });
        onStatusChange('ready');
      })
      .catch(() => {
        if (!active) return;
        onTokenChange(null);
        onStatusChange('error');
      });
    return () => {
      active = false;
      if (widgetIdRef.current && window.turnstile?.remove) {
        window.turnstile.remove(widgetIdRef.current);
      } else if (containerRef.current) {
        containerRef.current.innerHTML = '';
      }
      widgetIdRef.current = null;
      onTokenChange(null);
      onStatusChange('idle');
    };
  }, [action, onStatusChange, onTokenChange, siteKey, visible]);

  useEffect(() => {
    if (!visible || !widgetIdRef.current || !window.turnstile) return;
    window.turnstile.reset(widgetIdRef.current);
    onTokenChange(null);
    onStatusChange('ready');
  }, [visible, resetSignal, onStatusChange, onTokenChange]);

  if (!visible || !siteKey) {
    return null;
  }

  return <div ref={containerRef} data-turnstile-widget={widgetKey} />;
}
