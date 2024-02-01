import jsonlines
import json
import tqdm


def create_data_samples(path, out_path):
    # create data samples
    data = []
    with jsonlines.open(path) as reader:
        for transitions in tqdm.tqdm(reader):
            for step in transitions:
                context = step['extra context'].split(' \\n ')[-1]
                input_text = f"CONTEXT {context} GOAL {step['observation']} PROOFSTEP"
                output_text = step['action']
                data.append((input_text, output_text))
    with open(out_path, 'w') as f:
        json.dump(data, f, indent=4)



create_data_samples('data/old_output/val.jsonl', 'data/oneline_context/val.json')