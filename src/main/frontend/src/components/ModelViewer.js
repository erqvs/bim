import React from "react";
import {Empty} from "antd";
import {useTranslation} from "react-i18next";
import BimViewer from "./BimViewer";
import IfcViewer from "./IfcViewer";

const normalizeType = model => (model && model.type ? model.type.toLowerCase() : "svf");

export default ({models = [], ...props}) => {
    const {t} = useTranslation();

    if (!models || models.length === 0) {
        return <div className={props.className} style={props.style}>
            <Empty description={t('project.viewer.empty')}/>
        </div>;
    }

    const primaryType = normalizeType(models[0]),
        viewerModels = models.filter(model => normalizeType(model) === primaryType);

    if (primaryType === 'ifc') {
        return <IfcViewer {...props} models={viewerModels}/>;
    }
    if (primaryType === 'svf') {
        return <BimViewer {...props} models={viewerModels}/>;
    }
    return <div className={props.className} style={props.style}>
        <Empty description={t('project.viewer.unsupported')}/>
    </div>;
};
