/*******************************************************************************
 * Copyright (c) 2014 itemis AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Alexander Ny??en (itemis AG) - initial API and implementation
 *     
 *******************************************************************************/
package org.eclipse.gef4.fx.anchors;

import java.util.HashMap;
import java.util.Map;

import javafx.beans.property.ReadOnlyMapProperty;
import javafx.beans.property.ReadOnlyMapWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.transform.Transform;

import org.eclipse.gef4.fx.listeners.VisualChangeListener;
import org.eclipse.gef4.geometry.planar.Point;

public abstract class AbstractFXAnchor implements IFXAnchor {

	private ReadOnlyObjectWrapper<Node> anchorageProperty = new ReadOnlyObjectWrapper<Node>();
	private ReadOnlyMapWrapper<AnchorKey, Point> positionProperty = new ReadOnlyMapWrapper<AnchorKey, Point>(
			FXCollections.<AnchorKey, Point> observableHashMap());
	// private Map<Node, Set<AnchorKey>> keys = new HashMap<Node,
	// Set<AnchorKey>>();
	private Map<Node, VisualChangeListener> vcls = new HashMap<Node, VisualChangeListener>();
	private boolean vclsRegistered = false;

	private ChangeListener<Scene> anchorageVisualSceneChangeListener = new ChangeListener<Scene>() {
		@Override
		public void changed(ObservableValue<? extends Scene> observable,
				Scene oldValue, Scene newValue) {
			if (oldValue != null) {
				vclsRegistered = false;
				unregisterVCLs();
			}
			if (newValue != null) {
				registerVCLs();
				vclsRegistered = true;
			}
		}
	};

	private ChangeListener<Node> anchorageChangeListener = new ChangeListener<Node>() {
		@Override
		public void changed(ObservableValue<? extends Node> observable,
				Node oldAnchorage, Node newAnchorage) {
			if (oldAnchorage != null) {
				vclsRegistered = false;
				unregisterVCLs();
				oldAnchorage.sceneProperty().removeListener(
						anchorageVisualSceneChangeListener);
			}
			if (newAnchorage != null) {
				// register listener on scene property, so we can react to
				// changes of the scene property of the anchorage node
				newAnchorage.sceneProperty().addListener(
						anchorageVisualSceneChangeListener);
				// if scene is already set, register anchorage visual listener
				// directly (else do this within scene change listener)
				Scene scene = newAnchorage.getScene();
				if (scene != null) {
					registerVCLs();
					vclsRegistered = true;
				}
			}
		}
	};

	public AbstractFXAnchor(Node anchorage) {
		anchorageProperty.addListener(anchorageChangeListener);
		setAnchorageNode(anchorage);
	}

	@Override
	public ReadOnlyObjectProperty<Node> anchorageNodeProperty() {
		return anchorageProperty.getReadOnlyProperty();
	}

	@Override
	public void attach(Node anchored) {
		if (!vcls.containsKey(anchored)) {
			VisualChangeListener vcl = createVCL(anchored);
			vcls.put(anchored, vcl);
			if (vclsRegistered) {
				vcl.register(anchorageProperty.get(), anchored);
			}
		}
	}

	private VisualChangeListener createVCL(final Node anchored) {
		return new VisualChangeListener() {
			@Override
			protected void boundsChanged(Bounds oldBounds, Bounds newBounds) {
				recomputePositions(anchored);
			}

			@Override
			public void register(Node observed, Node observer) {
				super.register(observed, observer);
				/*
				 * The visual change listener is registered when the anchorage
				 * is attached to a Scene. Therefore, the anchorages
				 * bounds/transformation could have "changed" until
				 * registration, so we have to recompute anchored's positions
				 * now.
				 */
				recomputePositions(anchored);
			}

			@Override
			protected void transformChanged(Transform oldTransform,
					Transform newTransform) {
				recomputePositions(anchored);
			}
		};
	}

	@Override
	public void detach(Node anchored) {
		if (!vcls.containsKey(anchored)) {
			throw new IllegalArgumentException(
					"The given node is not attached to this IFXAnchor.");
		}
		VisualChangeListener vcl = vcls.remove(anchored);
		if (vclsRegistered) {
			vcl.unregister();
		}
		// TODO: remove all other entries for corresponding AnchorKeys
	}

	@Override
	public Node getAnchorageNode() {
		return anchorageProperty.get();
	}

	@Override
	public Point getPosition(AnchorKey key) {
		Node anchored = key.getAnchored();
		if (!vcls.containsKey(anchored)) {
			throw new IllegalArgumentException(
					"The anchored of the given key is not attached to this anchor.");
		}

		// if (!keys.containsKey(anchored)) {
		// keys.put(anchored, new HashSet<AnchorKey>());
		// }
		// keys.get(anchored).add(key);

		if (!positionProperty.containsKey(key)) {
			return null;
		}
		return positionProperty.get(key);
	}

	@Override
	public ReadOnlyMapProperty<AnchorKey, Point> positionProperty() {
		return positionProperty.getReadOnlyProperty();
	}

	// TODO: change to recomputePositions(Node anchored)
	protected abstract void recomputePositions(Node anchored);

	protected void registerVCLs() {
		for (Node anchored : vcls.keySet().toArray(new Node[] {})) {
			vcls.get(anchored).register(getAnchorageNode(), anchored);
		}
	}

	protected void setAnchorageNode(Node anchorage) {
		anchorageProperty.set(anchorage);
	}

	protected void unregisterVCLs() {
		for (Node anchored : vcls.keySet().toArray(new Node[] {})) {
			vcls.get(anchored).unregister();
		}
	}

}
