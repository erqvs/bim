import React, {useEffect, useRef, useState} from "react";
import {Card, Empty, Spin} from "antd";
import {useTranslation} from "react-i18next";
import * as THREE from "three";

const toAbsoluteUrl = mainPath => `${window.location.protocol}//${window.location.host}${mainPath}`;

const toPoint = vector => vector ? {
    x: Number(vector.x.toFixed(3)),
    y: Number(vector.y.toFixed(3)),
    z: Number(vector.z.toFixed(3))
} : null;

const formatPoint = point => point ? `${point.x}, ${point.y}, ${point.z}` : '-';

const unwrapValue = value => value && typeof value === 'object' && Object.prototype.hasOwnProperty.call(value, 'value') ? value.value : value;

const getLastSelectionMesh = viewer => {
    let mesh = null;
    viewer.IFC.selector.selection.meshes.forEach(item => {
        mesh = item;
    });
    return mesh;
};

const getCenterPoint = mesh => {
    if (!mesh || !mesh.geometry) {
        return null;
    }
    if (!mesh.geometry.boundingBox) {
        mesh.geometry.computeBoundingBox();
    }
    if (!mesh.geometry.boundingBox) {
        return null;
    }
    const center = new THREE.Vector3();
    mesh.geometry.boundingBox.getCenter(center);
    mesh.localToWorld(center);
    return toPoint(center);
};

export default (
    {
        models = [], loaded, success, error, successAllModels, errorAllModels, init, partSelect, style, className
    }
) => {
    const {t} = useTranslation(),
        viewerDiv = useRef(),
        viewerRef = useRef(),
        modelNamesRef = useRef({}),
        emit = useRef({}),
        [status, setStatus] = useState('loading'),
        [selectedItem, setSelectedItem] = useState();

    useEffect(() => {
        emit.current = {loaded, success, error, successAllModels, errorAllModels, init, partSelect};
    }, [loaded, success, error, successAllModels, errorAllModels, init, partSelect]);

    useEffect(() => {
        if (!viewerDiv.current || models.length === 0) {
            return undefined;
        }

        const container = viewerDiv.current;
        let disposed = false,
            viewer,
            cleanup = () => {
            };

        const boot = async () => {
            try {
                setStatus('loading');
                setSelectedItem(undefined);
                modelNamesRef.current = {};
                const {IfcViewerAPI} = await import('web-ifc-viewer');
                if (disposed || !container) {
                    return;
                }
                viewer = new IfcViewerAPI({
                    container,
                    backgroundColor: new THREE.Color(0xf3f5f7)
                });
                viewerRef.current = viewer;
                await viewer.IFC.setWasmPath(`${process.env.PUBLIC_URL}/sdk/ifc/`);
                viewer.axes.setAxes();
                viewer.grid.setGrid();
                emit.current.init && emit.current.init(viewer);

                const loadedModels = {};
                for (let index = 0; index < models.length; index += 1) {
                    const model = models[index];
                    const loadedModel = await viewer.IFC.loadIfcUrl(toAbsoluteUrl(model.mainPath), index === 0);
                    loadedModels[loadedModel.modelID] = loadedModel;
                    modelNamesRef.current[loadedModel.modelID] = model.name;
                    emit.current.loaded && emit.current.loaded(model.name, loadedModel, viewer);
                    emit.current.success && emit.current.success(loadedModel, viewer);
                }

                const domElement = viewer.context.getDomElement(),
                    handlePointerMove = () => viewer.IFC.selector.prePickIfcItem().catch(() => {
                    }),
                    handleClick = async () => {
                        const intersection = viewer.context.castRayIfc(),
                            picked = await viewer.IFC.selector.pickIfcItem(false);
                        if (!picked) {
                            setSelectedItem(null);
                            emit.current.partSelect && emit.current.partSelect(null, intersection, viewer);
                            return;
                        }
                        const properties = await viewer.IFC.getProperties(picked.modelID, picked.id, true, false),
                            centerPoint = getCenterPoint(getLastSelectionMesh(viewer)),
                            detail = {
                                modelID: picked.modelID,
                                elementID: picked.id,
                                modelName: modelNamesRef.current[picked.modelID],
                                name: unwrapValue(properties && properties.Name),
                                globalId: unwrapValue(properties && properties.GlobalId),
                                type: unwrapValue(properties && properties.type),
                                clickPoint: intersection && intersection.point ? toPoint(intersection.point) : null,
                                centerPoint
                            };
                        setSelectedItem(detail);
                        emit.current.partSelect && emit.current.partSelect(detail, intersection, viewer);
                    },
                    handleKeydown = event => {
                        if (event.key === 'Escape') {
                            viewer.IFC.selector.unpickIfcItems();
                            setSelectedItem(null);
                        }
                    };

                domElement.addEventListener('mousemove', handlePointerMove);
                domElement.addEventListener('click', handleClick);
                window.addEventListener('keydown', handleKeydown);
                cleanup = () => {
                    domElement.removeEventListener('mousemove', handlePointerMove);
                    domElement.removeEventListener('click', handleClick);
                    window.removeEventListener('keydown', handleKeydown);
                };

                setStatus('ready');
                emit.current.successAllModels && emit.current.successAllModels(loadedModels, viewer);
            } catch (e) {
                console.error('Load IFC model failed.', e);
                setStatus('error');
                emit.current.errorAllModels && emit.current.errorAllModels(e);
            }
        };

        boot();

        return () => {
            disposed = true;
            cleanup();
            setSelectedItem(undefined);
            if (viewerRef.current) {
                viewerRef.current.dispose().catch(() => {
                });
                viewerRef.current = null;
            }
            if (container) {
                container.innerHTML = '';
            }
        };
    }, [models]);

    return <div className={className} style={style || {}}>
        <div style={{position: 'relative', width: '100%', height: '100%', overflow: 'hidden', background: '#f3f5f7'}}>
            <div ref={viewerDiv} style={{width: '100%', height: '100%'}}/>
            {status === 'loading' && <div style={{
                position: 'absolute',
                inset: 0,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                background: 'rgba(255,255,255,0.72)',
                zIndex: 2
            }}>
                <Spin tip={t('project.viewer.loading')}/>
            </div>}
            {status === 'error' && <div style={{
                position: 'absolute',
                inset: 0,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                zIndex: 2
            }}>
                <Empty description={t('project.viewer.error')}/>
            </div>}
            {status === 'ready' && <Card size="small" title={t('project.viewer.selection')} style={{
                position: 'absolute',
                top: 16,
                right: 16,
                width: 320,
                zIndex: 2,
                background: 'rgba(255,255,255,0.92)'
            }}>
                {!selectedItem ? <div>{t('project.viewer.selectionHint')}</div> : <div style={{lineHeight: 1.8}}>
                    <div>{t('project.viewer.modelName')}: {selectedItem.modelName || '-'}</div>
                    <div>{t('project.viewer.modelId')}: {selectedItem.modelID}</div>
                    <div>{t('project.viewer.elementId')}: {selectedItem.elementID}</div>
                    <div>{t('project.viewer.name')}: {selectedItem.name || '-'}</div>
                    <div>{t('project.viewer.globalId')}: {selectedItem.globalId || '-'}</div>
                    <div>{t('project.viewer.type')}: {selectedItem.type || '-'}</div>
                    <div>{t('project.viewer.clickPoint')}: {formatPoint(selectedItem.clickPoint)}</div>
                    <div>{t('project.viewer.centerPoint')}: {formatPoint(selectedItem.centerPoint)}</div>
                </div>}
            </Card>}
        </div>
    </div>;
};
