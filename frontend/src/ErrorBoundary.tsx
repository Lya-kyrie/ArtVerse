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
        <div className="flex min-h-dvh items-center justify-center bg-gray-950 px-6 text-gray-100">
          <div className="max-w-md rounded-lg border border-red-500/20 bg-red-950/30 p-5">
            <div className="text-sm font-medium text-red-300">Application error</div>
            <div className="mt-2 text-sm text-gray-300">{this.state.error || 'Unknown error'}</div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
