import React, { useEffect } from 'react';
import * as am5 from '@amcharts/amcharts5';
import * as am5xy from '@amcharts/amcharts5/xy';
import am5themes_Animated from '@amcharts/amcharts5/themes/Animated';

function Bar5({ data, categoryField, valueField }) {

	useEffect(() => {

		// console.log(categoryField);
		// console.log(valueField);

		var root = am5.Root.new('bar-chart');

		root.setThemes([
		    am5themes_Animated.new(root)
		])

		var chart = root.container.children.push(am5xy.XYChart.new(root, {
		    panX: true,
		    panY: true,
		    wheelX: "panX",
		    wheelY: "zoomX",
		    pinchZoomX: true,
		}));

		var cursor = chart.set("cursor", am5xy.XYCursor.new(root, {}));
		cursor.lineY.set("visible", false);

		var xRenderer = am5xy.AxisRendererX.new(root, { minGridDistance: 30 });
		xRenderer.labels.template.setAll({ 
		    rotation: -90,
		    centerY: am5.p50,
		    centerX: am5.p100,
		    paddingRight: 15 
		});

		xRenderer.grid.template.setAll({ location: 1 });

		var xAxis = chart.xAxes.push(am5xy.CategoryAxis.new(root, {
		    renderer: xRenderer,
		    maxDeviation: 0.3,
		    categoryField: categoryField,
		    tooltip: am5.Tooltip.new(root, {})
		}));

		var yAxis = chart.yAxes.push(am5xy.ValueAxis.new(root, {
		    maxDeviation: 0.3,
		    renderer: am5xy.AxisRendererY.new(root, {
		        strokeOpacity: 0.1
		    }),
		}));

		var series = chart.series.push(am5xy.ColumnSeries.new(root, {
		    name: "Series 1",
		    xAxis: xAxis,
		    yAxis: yAxis,
		    valueYField: valueField,
		    sequencedInterpolation: true,
		    categoryXField: categoryField,
		    tooltip: am5.Tooltip.new(root, {
		        labelText: "{area}"
		    })
		}));

		series.columns.template.setAll({ cornerRadiusTopLeft: 5, cornerRadiusTopRight: 5, strokeOpacity: 0 });
		series.columns.template.adapters.add("fill", function(fill, target) {
		    return chart.get("colors").getIndex(series.columns.indexOf(target));
		});
		series.columns.template.adapters.add("stroke", function(stroke, target) {
		    return chart.get("colors").getIndex(series.columns.indexOf(target));
		});

		xAxis.data.setAll(data);
		series.data.setAll(data);

		series.appear(1000);
		chart.appear(1000, 100);

		

		return () => {
			chart.dispose();
			root.dispose();
		};
	}, [data, categoryField, valueField]);

	return (
		<div id="bar-chart" style={{ width: "100%", height: "70vh", position: "relative", alignItems: "center" }}></div>
	);
}

export default Bar5;