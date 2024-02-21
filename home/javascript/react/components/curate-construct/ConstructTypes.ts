interface ConstructName {
    typeAbbreviation: string;
    prefix: string;
    cassettes: Cassette[];
}

interface Cassette {
    cassetteNumber?: number;
    promoter: ConstructComponent[];
    coding: ConstructComponent[];
}

type ConstructComponent = {
    id: null | string;
    name: null | string;
    label: string;
    value: string;
    url: null | string;
    category: null | string;
    type: null | string;
    separator: string;
};

interface SimplifiedCassette {
    cassetteNumber?: number;
    promoter: string[];
    coding: string[];
}


export {ConstructName, Cassette, ConstructComponent, SimplifiedCassette};