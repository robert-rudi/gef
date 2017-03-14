/*******************************************************************************
 * Copyright (c) 2014, 2016 itemis AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Matthias Wienand (itemis AG) - initial API and implementation
 *     Alexander Nyßen (itemis AG) - refactorings
 *
 *******************************************************************************/
package org.eclipse.gef.mvc.fx.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;

import org.eclipse.gef.fx.nodes.InfiniteCanvas;
import org.eclipse.gef.geometry.planar.Dimension;
import org.eclipse.gef.mvc.fx.domain.IDomain;
import org.eclipse.gef.mvc.fx.parts.IVisualPart;
import org.eclipse.gef.mvc.fx.parts.PartUtils;
import org.eclipse.gef.mvc.fx.policies.IOnClickPolicy;
import org.eclipse.gef.mvc.fx.policies.IOnDragPolicy;
import org.eclipse.gef.mvc.fx.policies.IPolicy;
import org.eclipse.gef.mvc.fx.viewer.IViewer;
import org.eclipse.gef.mvc.fx.viewer.InfiniteCanvasViewer;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.event.EventTarget;
import javafx.event.EventType;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;

/**
 * An {@link ITool} to handle click/drag interaction gestures.
 * <p>
 * As click and drag are 'overlapping' gestures (a click is part of each drag,
 * which is composed out of click, drag, and release), these are handled
 * together here, even while distinct interaction policies will be queried to
 * handle the respective gesture parts.
 * <p>
 * During each click/drag interaction, the tool identifies respective
 * {@link IVisualPart}s that serve as interaction targets for click and drag
 * respectively. They are identified via hit-testing on the visuals and the
 * availability of a corresponding {@link IOnClickPolicy} or
 * {@link IOnDragPolicy}.
 * <p>
 * The {@link ClickDragTool} handles the opening and closing of an transaction
 * operation via the {@link IDomain}, to which it is adapted. It controls that a
 * single transaction operation is used for the complete interaction (including
 * the click and potential drag part), so all interaction results can be undone
 * in a single undo step.
 *
 * @author mwienand
 * @author anyssen
 *
 */
public class ClickDragTool extends AbstractTool {

	/**
	 * The typeKey used to retrieve those policies that are able to handle the
	 * click part of the click/drag interaction gesture.
	 */
	public static final Class<IOnClickPolicy> ON_CLICK_POLICY_KEY = IOnClickPolicy.class;

	/**
	 * The typeKey used to retrieve those policies that are able to handle the
	 * drag part of the click/drag interaction gesture.
	 */
	public static final Class<IOnDragPolicy> ON_DRAG_POLICY_KEY = IOnDragPolicy.class;

	private final Set<Scene> scenes = Collections
			.newSetFromMap(new IdentityHashMap<>());
	// TODO: Provide activeViewer in AbstractTool.
	private IViewer activeViewer;
	private Node pressed;
	private Point2D startMousePosition;

	/**
	 * This {@link EventHandler} is registered as an event filter on the
	 * {@link Scene} to handle drag and release events.
	 */
	private EventHandler<? super MouseEvent> mouseFilter = new EventHandler<MouseEvent>() {
		@Override
		public void handle(MouseEvent event) {
			// determine pressed/dragged/released state
			EventType<? extends Event> type = event.getEventType();
			if (pressed == null && type.equals(MouseEvent.MOUSE_PRESSED)) {
				EventTarget target = event.getTarget();
				if (target instanceof Node) {
					// initialize the gesture
					pressed = (Node) target;
					startMousePosition = new Point2D(event.getSceneX(),
							event.getSceneY());
					press(pressed, event);
				}
				return;
			} else if (pressed == null) {
				// not initialized yet
				return;
			}
			if (type.equals(MouseEvent.MOUSE_EXITED_TARGET)
					|| type.equals(MouseEvent.MOUSE_ENTERED_TARGET)) {
				// ignore mouse exited target events here (they may result from
				// visual changes that are caused by a preceding press)
				return;
			}
			boolean dragged = type.equals(MouseEvent.MOUSE_DRAGGED);
			boolean released = false;
			if (!dragged) {
				released = type.equals(MouseEvent.MOUSE_RELEASED);
				if (!released) {
					// account for missing RELEASE events
					if (!event.isPrimaryButtonDown()
							&& !event.isSecondaryButtonDown()
							&& !event.isMiddleButtonDown()) {
						// no button down
						released = true;
					}
				}
			}
			if (dragged || released) {
				double x = event.getSceneX();
				double dx = x - startMousePosition.getX();
				double y = event.getSceneY();
				double dy = y - startMousePosition.getY();
				if (dragged) {
					drag(pressed, event, dx, dy);
				} else {
					release(pressed, event, dx, dy);
					pressed = null;
				}
			}
		}
	};

	private ChangeListener<Boolean> viewerFocusChangeListener = new ChangeListener<Boolean>() {
		@Override
		public void changed(ObservableValue<? extends Boolean> observable,
				Boolean oldValue, Boolean newValue) {
			// cannot abort if no activeViewer
			if (activeViewer == null) {
				return;
			}
			// check if any viewer is focused
			for (IViewer v : getDomain().getViewers().values()) {
				if (v.isViewerFocused()) {
					return;
				}
			}
			// no viewer is focused => abort
			// cancel target policies
			for (IPolicy policy : getActivePolicies(activeViewer)) {
				if (policy instanceof IOnDragPolicy) {
					((IOnDragPolicy) policy).abortDrag();
				}
			}
			// clear active policies
			clearActivePolicies(activeViewer);
			activeViewer = null;
			// close execution transaction
			getDomain().closeExecutionTransaction(ClickDragTool.this);
		}
	};

	private final IOnDragPolicy indicationCursorPolicy[] = new IOnDragPolicy[] {
			null };
	@SuppressWarnings("unchecked")
	private final List<IOnDragPolicy> possibleDragPolicies[] = new ArrayList[] {
			null };

	private EventHandler<MouseEvent> indicationCursorMouseMoveFilter = new EventHandler<MouseEvent>() {
		@Override
		public void handle(MouseEvent event) {
			if (indicationCursorPolicy[0] != null) {
				indicationCursorPolicy[0].hideIndicationCursor();
				indicationCursorPolicy[0] = null;
			}

			EventTarget eventTarget = event.getTarget();
			if (eventTarget instanceof Node) {
				// determine all drag policies that can be
				// notified about events
				Node target = (Node) eventTarget;
				IViewer viewer = PartUtils.retrieveViewer(getDomain(), target);
				if (viewer != null) {
					possibleDragPolicies[0] = new ArrayList<>(
							getTargetPolicyResolver().resolvePolicies(
									ClickDragTool.this, target, viewer,
									ON_DRAG_POLICY_KEY));
				} else {
					possibleDragPolicies[0] = new ArrayList<>();
				}

				// search drag policies in reverse order first,
				// so that the policy closest to the target part
				// is the first policy to provide an indication
				// cursor
				ListIterator<? extends IOnDragPolicy> dragIterator = possibleDragPolicies[0]
						.listIterator(possibleDragPolicies[0].size());
				while (dragIterator.hasPrevious()) {
					IOnDragPolicy policy = dragIterator.previous();
					if (policy.showIndicationCursor(event)) {
						indicationCursorPolicy[0] = policy;
						break;
					}
				}
			}
		}
	};

	private EventHandler<KeyEvent> indicationCursorKeyFilter = new EventHandler<KeyEvent>() {
		@Override
		public void handle(KeyEvent event) {
			if (indicationCursorPolicy[0] != null) {
				indicationCursorPolicy[0].hideIndicationCursor();
				indicationCursorPolicy[0] = null;
			}

			if (possibleDragPolicies[0] == null
					|| possibleDragPolicies[0].isEmpty()) {
				return;
			}

			// search drag policies in reverse order first,
			// so that the policy closest to the target part
			// is the first policy to provide an indication
			// cursor
			ListIterator<? extends IOnDragPolicy> dragIterator = possibleDragPolicies[0]
					.listIterator(possibleDragPolicies[0].size());
			while (dragIterator.hasPrevious()) {
				IOnDragPolicy policy = dragIterator.previous();
				if (policy.showIndicationCursor(event)) {
					indicationCursorPolicy[0] = policy;
					break;
				}
			}
		}
	};

	@Override
	protected void doActivate() {
		super.doActivate();
		for (final IViewer viewer : getDomain().getViewers().values()) {
			// register a viewer focus change listener
			viewer.viewerFocusedProperty()
					.addListener(viewerFocusChangeListener);

			Scene scene = viewer.getCanvas().getScene();
			if (scenes.contains(scene)) {
				// already registered for this scene
				continue;
			}

			// register mouse move filter for forwarding events to drag policies
			// that can show a mouse cursor to indicate their action
			scene.addEventFilter(MouseEvent.MOUSE_MOVED,
					indicationCursorMouseMoveFilter);
			// register key event filter for forwarding events to drag policies
			// that can show a mouse cursor to indicate their action
			scene.addEventFilter(KeyEvent.ANY, indicationCursorKeyFilter);
			// register mouse filter for forwarding press, drag, and release
			// events
			scene.addEventFilter(MouseEvent.ANY, mouseFilter);
			scenes.add(scene);
		}
	}

	@Override
	protected void doDeactivate() {
		for (Scene scene : new ArrayList<>(scenes)) {
			scene.removeEventFilter(MouseEvent.ANY, mouseFilter);
			scene.removeEventFilter(MouseEvent.MOUSE_MOVED,
					indicationCursorMouseMoveFilter);
			scene.removeEventFilter(KeyEvent.ANY, indicationCursorKeyFilter);
		}
		for (final IViewer viewer : getDomain().getViewers().values()) {
			viewer.viewerFocusedProperty()
					.removeListener(viewerFocusChangeListener);
		}
		super.doDeactivate();
	}

	/**
	 * This method is called upon {@link MouseEvent#MOUSE_DRAGGED} events.
	 *
	 * @param target
	 *            The event target.
	 * @param event
	 *            The corresponding {@link MouseEvent}.
	 * @param dx
	 *            The horizontal displacement from the mouse press location.
	 * @param dy
	 *            The vertical displacement from the mouse press location.
	 */
	protected void drag(Node target, MouseEvent event, double dx, double dy) {
		// abort processing of this gesture if no policies could be
		// found that can process it
		if (getActivePolicies(activeViewer).isEmpty()) {
			return;
		}

		for (IOnDragPolicy policy : getActivePolicies(activeViewer)) {
			policy.drag(event, new Dimension(dx, dy));
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public List<IOnDragPolicy> getActivePolicies(IViewer viewer) {
		return (List<IOnDragPolicy>) super.getActivePolicies(viewer);
	}

	/**
	 * This method is called upon {@link MouseEvent#MOUSE_PRESSED} events.
	 *
	 * @param target
	 *            The event target.
	 * @param event
	 *            The corresponding {@link MouseEvent}.
	 */
	protected void press(Node target, MouseEvent event) {
		IViewer viewer = PartUtils.retrieveViewer(getDomain(), target);
		if (viewer instanceof InfiniteCanvasViewer) {
			InfiniteCanvas canvas = ((InfiniteCanvasViewer) viewer).getCanvas();
			// if any node in the target hierarchy is a scrollbar,
			// do not process the event
			if (event.getTarget() instanceof Node) {
				Node targetNode = (Node) event.getTarget();
				while (targetNode != null) {
					if (targetNode == canvas.getHorizontalScrollBar()
							|| targetNode == canvas.getVerticalScrollBar()) {
						return;
					}
					targetNode = targetNode.getParent();
				}
			}
		}

		// show indication cursor on press so that the indication
		// cursor is shown even when no mouse move event was
		// previously fired
		indicationCursorMouseMoveFilter.handle(event);

		// disable indication cursor event filters within
		// press-drag-release gesture
		Scene scene = viewer.getRootPart().getVisual().getScene();
		scene.removeEventFilter(MouseEvent.MOUSE_MOVED,
				indicationCursorMouseMoveFilter);
		scene.removeEventFilter(KeyEvent.ANY, indicationCursorKeyFilter);

		// determine viewer that contains the given target part
		viewer = PartUtils.retrieveViewer(getDomain(), target);
		// determine click policies
		boolean opened = false;
		List<? extends IOnClickPolicy> clickPolicies = getTargetPolicyResolver()
				.resolvePolicies(ClickDragTool.this, target, viewer,
						ON_CLICK_POLICY_KEY);
		// process click first
		if (clickPolicies != null && !clickPolicies.isEmpty()) {
			opened = true;
			getDomain().openExecutionTransaction(ClickDragTool.this);
			for (IOnClickPolicy clickPolicy : clickPolicies) {
				clickPolicy.click(event);
			}
		}

		// determine viewer that contains the given target part
		// again, now that the click policies have been executed
		activeViewer = PartUtils.retrieveViewer(getDomain(), target);

		// determine drag policies
		List<? extends IOnDragPolicy> policies = null;
		if (activeViewer != null) {
			// XXX: A click policy could have changed the visual
			// hierarchy so that the viewer cannot be determined for
			// the target node anymore. If that is the case, no drag
			// policies should be notified about the event.
			policies = getTargetPolicyResolver().resolvePolicies(
					ClickDragTool.this, target, activeViewer,
					ON_DRAG_POLICY_KEY);
		}

		// abort processing of this gesture if no drag policies
		// could be found
		if (policies == null || policies.isEmpty()) {
			// remove this tool from the domain's execution
			// transaction if previously opened
			if (opened) {
				getDomain().closeExecutionTransaction(ClickDragTool.this);
			}
			policies = null;
			return;
		}

		// add this tool to the execution transaction of the domain
		// if not yet opened
		if (!opened) {
			getDomain().openExecutionTransaction(ClickDragTool.this);
		}

		// mark the drag policies as active
		setActivePolicies(activeViewer, policies);

		// send press() to all drag policies
		for (IOnDragPolicy policy : policies) {
			policy.startDrag(event);
		}
	}

	/**
	 * This method is called upon {@link MouseEvent#MOUSE_RELEASED} events. This
	 * method is also called for other mouse events, when a mouse release event
	 * was not fired, but was detected otherwise (probably only possible when
	 * using the JavaFX/SWT integration).
	 *
	 * @param target
	 *            The event target.
	 * @param event
	 *            The corresponding {@link MouseEvent}.
	 * @param dx
	 *            The horizontal displacement from the mouse press location.
	 * @param dy
	 *            The vertical displacement from the mouse press location.
	 */
	protected void release(Node target, MouseEvent event, double dx,
			double dy) {
		// enable indication cursor event filters outside of
		// press-drag-release gesture
		Scene scene = target.getScene();
		scene.addEventFilter(MouseEvent.MOUSE_MOVED,
				indicationCursorMouseMoveFilter);
		scene.addEventFilter(KeyEvent.ANY, indicationCursorKeyFilter);

		// abort processing of this gesture if no policies could be
		// found that can process it
		if (getActivePolicies(activeViewer).isEmpty()) {
			activeViewer = null;
			return;
		}

		// send release() to all drag policies
		for (IOnDragPolicy policy : getActivePolicies(activeViewer)) {
			policy.endDrag(event, new Dimension(dx, dy));
		}

		// clear active policies before processing release
		clearActivePolicies(activeViewer);
		activeViewer = null;

		// remove this tool from the domain's execution transaction
		getDomain().closeExecutionTransaction(ClickDragTool.this);

		// hide indication cursor
		if (indicationCursorPolicy[0] != null) {
			indicationCursorPolicy[0].hideIndicationCursor();
			indicationCursorPolicy[0] = null;
		}
	}
}
