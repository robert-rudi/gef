/*******************************************************************************
 * Copyright (c) 2014, 2015 itemis AG and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Matthias Wienand (itemis AG) - initial API & implementation
 *
 *******************************************************************************/
package org.eclipse.gef4.zest.fx.behaviors;

import org.eclipse.core.commands.ExecutionException;
import org.eclipse.gef4.geometry.convert.fx.FX2Geometry;
import org.eclipse.gef4.geometry.planar.Dimension;
import org.eclipse.gef4.geometry.planar.Point;
import org.eclipse.gef4.layout.LayoutProperties;
import org.eclipse.gef4.mvc.fx.policies.FXResizePolicy;
import org.eclipse.gef4.mvc.fx.policies.FXTransformPolicy;
import org.eclipse.gef4.mvc.operations.ITransactionalOperation;
import org.eclipse.gef4.mvc.parts.IContentPart;
import org.eclipse.gef4.zest.fx.layout.GraphNodeLayout;
import org.eclipse.gef4.zest.fx.parts.NodePart;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.transform.Affine;

/**
 * The {@link NodeLayoutBehavior} is a {@link NodePart}-specific
 * {@link AbstractLayoutBehavior} implementation.
 *
 * @author mwienand
 *
 */
// only applicable to NodePart (see #getHost())
public class NodeLayoutBehavior extends AbstractLayoutBehavior {

	@Override
	protected void adaptFromLayout() {
		NodePart nodePart = getHost();
		Node visual = nodePart.getVisual();
		Bounds layoutBounds = visual.getLayoutBounds();
		Affine transform = nodePart.getAdapter(FXTransformPolicy.TRANSFORM_PROVIDER_KEY).get();
		double x = transform.getTx();
		double y = transform.getTy();
		double w = layoutBounds.getWidth();
		double h = layoutBounds.getHeight();

		GraphNodeLayout nodeLayout = getNodeLayout();
		Point location = LayoutProperties.getLocation(nodeLayout);
		Dimension size = LayoutProperties.getSize(nodeLayout);

		// location is the center of the node, therefore we subtract half
		// width/height from it
		double dx = location.x - size.width / 2 - x;
		double dy = location.y - size.height / 2 - y;
		double dw = size.width - w;
		double dh = size.height - h;

		FXResizePolicy resizePolicy = nodePart.getAdapter(FXResizePolicy.class);
		resizePolicy.init();
		resizePolicy.resize(dw, dh);
		ITransactionalOperation resizeOperation = resizePolicy.commit();
		if (resizeOperation != null) {
			try {
				resizeOperation.execute(null, null);
			} catch (ExecutionException e) {
				throw new IllegalStateException(e);
			}
		}

		FXTransformPolicy transformPolicy = nodePart.getAdapter(FXTransformPolicy.class);
		transformPolicy.init();
		transformPolicy.setTransform(FX2Geometry.toAffineTransform(transform).setToTranslation(x + dx, y + dy));
		ITransactionalOperation transformOperation = transformPolicy.commit();
		if (transformOperation != null) {
			try {
				transformOperation.execute(null, null);
			} catch (ExecutionException e) {
				throw new IllegalStateException(e);
			}
		}
		nodePart.refreshVisual();
	}

	@Override
	protected GraphLayoutBehavior getGraphLayoutBehavior() {
		IContentPart<Node, ? extends Node> graphPart = getHost().getRoot().getViewer().getContentPartMap()
				.get(getHost().getContent().getGraph());
		return graphPart.getAdapter(GraphLayoutBehavior.class);
	}

	@Override
	public NodePart getHost() {
		return (NodePart) super.getHost();
	}

	/**
	 * Returns the {@link GraphNodeLayout} that corresponds to the
	 * {@link NodePart} on which this {@link NodeLayoutBehavior} is installed.
	 *
	 * @return The {@link GraphNodeLayout} that corresponds to the
	 *         {@link NodePart} on which this {@link NodeLayoutBehavior} is
	 *         installed.
	 */
	protected GraphNodeLayout getNodeLayout() {
		// TODO: use event to update node layout
		GraphNodeLayout nodeLayout = getGraphLayoutBehavior().getGraphLayoutContext()
				.getNodeLayout(getHost().getContent());
		if (nodeLayout == null) {
			throw new IllegalStateException("Cannot find INodeLayout in NavigationModel.");
		}
		return nodeLayout;
	}

	@Override
	protected void provideToLayout() {
		Node visual = getHost().getVisual();
		Bounds hostBounds = visual.getLayoutBounds();
		double minx = hostBounds.getMinX();
		double miny = hostBounds.getMinY();
		double maxx = hostBounds.getMaxX();
		double maxy = hostBounds.getMaxY();

		Affine transform = getHost().getAdapter(FXTransformPolicy.TRANSFORM_PROVIDER_KEY).get();

		GraphNodeLayout nodeLayout = getNodeLayout();
		LayoutProperties.setLocation(nodeLayout, transform.getTx() + minx, transform.getTy() + miny);
		LayoutProperties.setSize(nodeLayout, maxx - minx, maxy - miny);
		LayoutProperties.setResizable(nodeLayout, visual.isResizable());
	}

}
