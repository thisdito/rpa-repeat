package nativehooks.windows;

import java.awt.event.InputEvent;

import globalListener.NativeMouseEvent;
import globalListener.NativeMouseEvent.State;
import nativehooks.NativeHookMouseEvent;
import nativehooks.UnknownKeyEventException;
import nativehooks.UnknownMouseEventException;

class WindowsNativeMouseEvent extends NativeHookMouseEvent {
	private final int x;
	private final int y;

	private final int code;

	private WindowsNativeMouseEvent(int x, int y, int code) {
		this.x = x;
		this.y = y;
		this.code = code;
	}

	protected static WindowsNativeMouseEvent of(int x, int y, int code) {
		return new WindowsNativeMouseEvent(x, y, code);
	}

	@Override
	public NativeMouseEvent convertEvent() throws UnknownMouseEventException {
		State s;
		int button;

		switch (code) {
		case 512:
			s = State.UNKNOWN;
			button = 0;
			break;
		case 513:
			s = State.PRESSED;
			button = InputEvent.BUTTON1_DOWN_MASK;
			break;
		case 514:
			s = State.RELEASED;
			button = InputEvent.BUTTON1_DOWN_MASK;
			break;
		case 516:
			s = State.PRESSED;
			button = InputEvent.BUTTON3_DOWN_MASK;
			break;
		case 517:
			s = State.RELEASED;
			button = InputEvent.BUTTON3_DOWN_MASK;
			break;
		default:
			throw new UnknownKeyEventException("Unknown code " + code + ".");
		}

		return NativeMouseEvent.of(x, y, s, button);
	}
}