import { Component, type ReactNode } from 'react';

export default class ErrorBoundary extends Component<
  { children: ReactNode },
  { hasError: boolean; error?: string }
> {
  constructor(props: { children: ReactNode }) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(error: Error) {
    return { hasError: true, error: error.message };
  }

  override render() {
    if (this.state.hasError) {
      return (
        <div className="flex min-h-dvh items-center justify-center bg-bg-base px-6 text-text-primary">
          <div className="max-w-md rounded-lg border border-accent/20 bg-accent-soft p-5">
            <div className="text-sm font-medium text-danger">应用出错</div>
            <div className="mt-2 text-sm text-text-secondary">{this.state.error || 'Unknown error'}</div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
